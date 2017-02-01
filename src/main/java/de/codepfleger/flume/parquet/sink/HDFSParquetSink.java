package de.codepfleger.flume.parquet.sink;

import de.codepfleger.flume.avro.serializer.event.WindowsLogEvent;
import de.codepfleger.flume.avro.serializer.serializer.AbstractReflectionAvroEventSerializer;
import de.codepfleger.flume.parquet.serializer.ParquetSerializer;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.flume.*;
import org.apache.flume.conf.Configurable;
import org.apache.flume.formatter.output.BucketPath;
import org.apache.flume.serialization.EventSerializer;
import org.apache.flume.serialization.EventSerializerFactory;
import org.apache.flume.sink.AbstractSink;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.ShutdownHookManager;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Date;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

public class HDFSParquetSink extends AbstractSink implements Configurable {
    public static final String EVENTS_PER_TRANSACTION_KEY = "eventsPerTransaction";
    public static final String FILE_PATH_KEY = "filePath";
    public static final String FILE_SIZE_KEY = "fileSize";
    public static final String FILE_COMPRESSION_KEY = "fileCompression";
    public static final String FILE_QUEUE_SIZE_KEY = "fileQueueSize";
    public static final String TIMEOUT_SECONDS_KEY = "timeoutSeconds";

    private static final Logger LOG = LoggerFactory.getLogger(HDFSParquetSink.class);

    private final Object lock = new Object();
    private final Random random = new Random();

    private static final AtomicBoolean processingEnabled = new AtomicBoolean(false);

    private SerializerLinkedHashMap serializers;

    private CompressionCodecName compressionCodec;
    private int eventsPerTransaction;
    private int timeoutSeconds;
    private String filePath;
    private Integer uncompressedFileSize;
    private String serializerType;
    private Context serializerContext;

    @Override
    public synchronized void start() {
        super.start();
        final HDFSParquetSink sink = this;
        ShutdownHookManager.get().addShutdownHook(new Runnable() {
            @Override
            public void run() {
                sink.stop();
            }
        }, Integer.MAX_VALUE);
        processingEnabled.getAndSet(true);
    }

    @Override
    public synchronized void stop() {
        processingEnabled.getAndSet(false);
        synchronized (lock) {
            if(serializers != null && !serializers.isEmpty()) {
                for (SerializerMapEntry serializer : serializers.values()) {
                    try {
                        serializer.close();
                    } catch (IOException e) {
                        LOG.error(e.getMessage(), e);
                    }
                }
                serializers.clear();
            }
        }
        super.stop();
    }

    @Override
    public Status process() throws EventDeliveryException {
        if(processingEnabled.get()) {
            Channel ch = getChannel();
            Transaction txn = ch.getTransaction();
            txn.begin();
            try {
                for(int i=0; i<eventsPerTransaction; i++) {
                    Event event = ch.take();
                    if (event != null) {
                        getSerializer(event).write(event);
                    }
                }
                txn.commit();
                return Status.READY;
            } catch (Throwable t) {
                txn.rollback();
                return Status.BACKOFF;
            } finally {
                txn.close();
            }
        }
        return Status.READY;
    }

    private EventSerializer getSerializer(Event event) throws IOException {
        String filePath = getFilePathFromEvent(event);
        synchronized (lock) {
            SerializerMapEntry eventSerializer = serializers.get(filePath);
            if(isSerializerInvalid(eventSerializer)) {
                eventSerializer.close();
                serializers.remove(filePath);
                eventSerializer = null;
            }
            if(eventSerializer == null) {
                eventSerializer = createSerializer(filePath);
                serializers.put(filePath, eventSerializer);
            }
            return eventSerializer.getSerializer();
        }
    }

    private boolean isSerializerInvalid(SerializerMapEntry eventSerializer) {
        if(eventSerializer != null) {
            if(eventSerializer.getSerializer().getWriter().getDataSize() > uncompressedFileSize) {
                return true;
            }
            long time = new Date().getTime();
            long serializerTimeout = eventSerializer.getStartTime() + (timeoutSeconds * 1000);
            if(time > serializerTimeout) {
                return true;
            }
        }
        return false;
    }

    private SerializerMapEntry createSerializer(String filePath) throws IOException {
        ParquetSerializer eventSerializer;
        eventSerializer = (ParquetSerializer) EventSerializerFactory.getInstance(serializerType, serializerContext, null);
        String targetFilePath = getActualFilePath(filePath);
        String workingFilePath = "_" + targetFilePath;
        ParquetWriter<GenericData.Record> writer = AvroParquetWriter.<GenericData.Record>builder(new Path(workingFilePath))
                .withSchema(getSchema()).withCompressionCodec(compressionCodec).build();
        eventSerializer.initialize(writer, getSchema());
        return new SerializerMapEntry(workingFilePath, targetFilePath, eventSerializer);
    }

    protected Schema getSchema() {
        //TODO event class configurable
        return new Schema.Parser().parse(AbstractReflectionAvroEventSerializer.createSchema(WindowsLogEvent.class));
    }

    private String getActualFilePath(String actualFilePath) {
        int nextInt = Math.abs(random.nextInt());
        if(actualFilePath.contains("%[n]")) {
            actualFilePath = actualFilePath.replace("%[n]", "" + nextInt);
        } else {
            actualFilePath += "." + nextInt;
        }
        return actualFilePath;
    }

    private String getFilePathFromEvent(Event event) {
        return BucketPath.escapeString(filePath, event.getHeaders(), null, false, 0, 1, true);
    }

    @Override
    public void configure(Context context) {
        filePath = context.getString(FILE_PATH_KEY);
        if(filePath == null) {
            throw new IllegalStateException("filePath missing");
        }
        serializerType = context.getString("serializer");
        if(serializerType == null) {
            throw new IllegalStateException("filePath missing");
        }

        compressionCodec = CompressionCodecName.fromConf(context.getString(FILE_COMPRESSION_KEY, CompressionCodecName.SNAPPY.name()));
        eventsPerTransaction = context.getInteger(EVENTS_PER_TRANSACTION_KEY, 10);
        uncompressedFileSize = context.getInteger(FILE_SIZE_KEY, 500000);
        timeoutSeconds = context.getInteger(TIMEOUT_SECONDS_KEY, 3600);
        serializers = new SerializerLinkedHashMap(context.getInteger(FILE_QUEUE_SIZE_KEY, 2));
        serializerContext = new Context(context.getSubProperties(EventSerializer.CTX_PREFIX));
    }
}