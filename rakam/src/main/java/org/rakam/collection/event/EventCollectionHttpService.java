package org.rakam.collection.event;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.google.common.reflect.TypeToken;
import io.airlift.log.Logger;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpVersion;
import org.rakam.collection.Event;
import org.rakam.collection.event.metastore.Metastore;
import org.rakam.plugin.EventMapper;
import org.rakam.plugin.EventStore;
import org.rakam.server.http.HttpService;
import org.rakam.server.http.RakamHttpRequest;
import org.rakam.server.http.annotations.Api;
import org.rakam.server.http.annotations.ApiOperation;
import org.rakam.server.http.annotations.ApiParam;
import org.rakam.server.http.annotations.ApiResponse;
import org.rakam.server.http.annotations.ApiResponses;
import org.rakam.server.http.annotations.Authorization;
import org.rakam.util.RakamException;

import javax.inject.Inject;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.ImmutableMap.of;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static org.rakam.collection.event.metastore.Metastore.AccessKeyType.WRITE_KEY;
import static org.rakam.util.JsonHelper.encode;

@Path("/event")
@Api(value = "/event", description = "Event collection module", tags = {"event"})
public class EventCollectionHttpService extends HttpService {
    final static Logger LOGGER = Logger.get(EventCollectionHttpService.class);
    private final ObjectMapper jsonMapper = new ObjectMapper(new EventParserJsonFactory());
    private static final Charset UTF8_CHARSET = Charset.forName("UTF-8");
    private final ByteBuf OK_MESSAGE = Unpooled.unreleasableBuffer(Unpooled.wrappedBuffer("1".getBytes(UTF8_CHARSET)));

    private final EventStore eventStore;
    private final Set<EventMapper> mappers;
    private final Metastore metastore;

    @Inject
    public EventCollectionHttpService(EventStore eventStore, EventDeserializer deserializer, Set<EventMapper> mappers, Metastore metastore) {
        this.eventStore = eventStore;
        this.mappers = mappers;
        this.metastore = metastore;

        SimpleModule module = new SimpleModule();
        module.addDeserializer(Event.class, deserializer);
        jsonMapper.registerModule(module);
    }

    private List<Map.Entry<String, String>> mapEvent(Event event, HttpHeaders headers, InetAddress socketAddress) {
        List<Map.Entry<String, String>> responseAttachment = null;
        for (EventMapper mapper : mappers) {
            try {
                // TODO: bound event mappers to Netty Channels and run them in separate thread
                final Iterable<Map.Entry<String, String>> map = mapper.map(event, headers, socketAddress);
                if(map != null) {
                    if(responseAttachment == null) {
                        responseAttachment = new ArrayList<>();
                    }

                    for (Map.Entry<String, String> entry : map) {
                        responseAttachment.add(entry);
                    }
                }
            } catch (Exception e) {
                throw new RuntimeException("An error occurred while processing event in " + mapper.getClass().getName(), e);
            }
        }
        return responseAttachment;
    }

    private boolean storeEvents(List<Event> events) {
        try {
            eventStore.storeBatch(events);
        } catch (Exception e) {
            LOGGER.error(e, "error while storing event.");
            return false;
        }

        return true;
    }


    @POST
    @ApiOperation(value = "Collect event", response = Integer.class, request = EventBean.class,
            authorizations = @Authorization(value = "write_key")
    )
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.")})
    @Path("/collect")
    public void collectEvent(RakamHttpRequest request) {

        InetSocketAddress socketAddress = (InetSocketAddress) request.context().channel()
                .remoteAddress();
        HttpHeaders headers = request.headers();
        String checksum = headers.get("Content-MD5");

        request.bodyHandler(buff -> {
            if (checksum != null && !validateChecksum(request, checksum, buff)) {
                return;
            }

            final List<Map.Entry<String, String>> entries;

            try {
                Event event = jsonMapper.readValue(buff, Event.class);
                if(validateProjectPermission(event.project(), headers.get("write_key"))) {
                    request.response("\"api key is invalid\"", UNAUTHORIZED).end();
                }

                try {
                    entries = mapEvent(event, headers, socketAddress.getAddress());
                } catch (Exception e) {
                    LOGGER.error(e);
                    request.response("0", BAD_REQUEST).end();
                    return;
                }

                try {
                    eventStore.store(event);
                } catch (Exception e) {
                    LOGGER.error(e, "error while storing event.");
                    request.response("0", BAD_REQUEST).end();
                    return;
                }
            } catch (JsonMappingException e) {
                request.response("\""+e.getMessage()+"\"", BAD_REQUEST).end();
                return;
            } catch (IOException e) {
                request.response("\"json couldn't parsed\"", BAD_REQUEST).end();
                return;
            } catch (RakamException e) {
                request.response(e.getMessage(), BAD_REQUEST).end();
                return;
            } catch (Exception e) {
                LOGGER.error(e, "Error while collecting event");
                request.response("\"internal server error\"", INTERNAL_SERVER_ERROR).end();
                return;
            }

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, OK, OK_MESSAGE);
            if(entries != null) {
                final HttpHeaders responseHeaders = response.headers();
                for (Map.Entry<String, String> entry : entries) {
                    responseHeaders.set(entry.getKey(), entry.getValue());
                }
            }
            request.response(response).end();
        });
    }

    private boolean validateProjectPermission(String project, String writeKey) {
        if(writeKey == null) {
            return false;
        }

        return metastore.checkPermission(project, WRITE_KEY, writeKey);
    }

    static final class ListRequestEventBean extends TypeToken<List<EventBean>> {}
    static final class ListResponseEventBean extends TypeToken<List<Integer>> {}

    @POST
    @ApiOperation(value = "Collect multiple events", request = ListRequestEventBean.class, response = ListResponseEventBean.class,
            authorizations = @Authorization(value = "write_key")
    )
    @ApiResponses(value = {
            @ApiResponse(code = 400, message = "Project does not exist.")})
    @Path("/batch")
    public void batchEvents(RakamHttpRequest request) {

        InetSocketAddress socketAddress = (InetSocketAddress) request.context().channel()
                .remoteAddress();
        HttpHeaders headers = request.headers();
        String checksum = headers.get("Content-MD5");
        String write_key = headers.get("write_key");

        request.bodyHandler(buff -> {
            if (checksum != null && !validateChecksum(request, checksum, buff)) {
                return;
            }

            List<Map.Entry<String, String>> entries = null;

            try {
                List<Event> events = jsonMapper.readValue(buff, List.class);

                if(events.size() > 0) {
                    final Event event = events.get(0);
                    String project = event.project();
                    if(!validateProjectPermission(project, write_key)) {
                        request.response("\"api key is invalid\"", UNAUTHORIZED).end();
                        return;
                    }
                    for (int i = 1; i < events.size(); i++) {
                        if(!events.get(i).project().equals(project)) {
                            request.response("\"all events must belong to same project. try inserting events one by one.\"", UNAUTHORIZED).end();
                        }
                    }
                }

                for (Event event : events) {
                    try {
                        List<Map.Entry<String, String>> mapperEntries = mapEvent(event, headers, socketAddress.getAddress());
                        if(mapperEntries != null) {
                            if(entries == null) {
                                entries = new ArrayList<>();
                            }
                            entries.addAll(mapperEntries);
                        }
                        eventStore.store(event);
                    } catch (Exception e) {
                        LOGGER.error(e);
                        request.response("0", BAD_REQUEST).end();
                        return;
                    }
                }

            } catch (JsonMappingException e) {
                request.response(e.getMessage(), BAD_REQUEST).end();
                return;
            } catch (IOException e) {
                request.response("json couldn't parsed", BAD_REQUEST).end();
                return;
            } catch (RakamException e) {
                request.response(e.getMessage(), BAD_REQUEST).end();
                return;
            } catch (Exception e) {
                LOGGER.error(e, "Error while collecting event");
                request.response(e.getMessage(), INTERNAL_SERVER_ERROR).end();
                return;
            }

            DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, OK, OK_MESSAGE);
            if(entries != null) {
                final HttpHeaders responseHeaders = response.headers();
                for (Map.Entry<String, String> entry : entries) {
                    responseHeaders.set(entry.getKey(), entry.getValue());
                }
            }
            request.response(response).end();
        });
    }

    private boolean validateChecksum(RakamHttpRequest request, String checksum, String expected) {
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            request.response("Internal Error", INTERNAL_SERVER_ERROR).end();
            return false;
        }
        if (!md.digest(checksum.getBytes(UTF8_CHARSET))
                .equals(expected.getBytes(UTF8_CHARSET))) {
            request.response(encode(of("error", "checksum is invalid")),
                    BAD_REQUEST).end();
            return false;
        }

        return true;
    }

    public static class EventParserJsonFactory extends JsonFactory {

        @Override
        protected JsonParser _createParser(char[] data, int offset, int len, IOContext ctxt,
                                           boolean recyclable) throws IOException {
            return new EventDeserializer.SaveableReaderBasedJsonParser(ctxt, _parserFeatures, null, _objectCodec,
                    _rootCharSymbols.makeChild(_factoryFeatures),
                    data, offset, offset + len, recyclable);
        }
    }

    // we use this class only to create model in swagger definition.
    private static class EventBean {
        public final String project;
        public final String collection;
        public final Map<String, Object> properties;

        @JsonCreator
        public EventBean(@ApiParam(name = "project") String project,
                          @ApiParam(name = "collection") String collection,
                          @ApiParam(name= "properties") Map<String, Object> properties) {
            this.project = project;
            this.collection = collection;
            this.properties = properties;
        }
    }

}
