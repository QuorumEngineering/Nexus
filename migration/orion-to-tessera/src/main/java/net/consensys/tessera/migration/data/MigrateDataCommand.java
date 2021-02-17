package net.consensys.tessera.migration.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.BlockingWaitStrategy;
import com.lmax.disruptor.EventHandler;
import com.lmax.disruptor.ExceptionHandler;
import com.lmax.disruptor.dsl.Disruptor;
import com.lmax.disruptor.dsl.ProducerType;
import com.quorum.tessera.encryption.Encryptor;
import com.quorum.tessera.encryption.EncryptorFactory;
import net.consensys.tessera.migration.OrionKeyHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.persistence.EntityManagerFactory;
import javax.persistence.Persistence;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ThreadFactory;

public class MigrateDataCommand implements Callable<Boolean> {

    private static final Logger LOGGER = LoggerFactory.getLogger(MigrateDataCommand.class);

    private TesseraJdbcOptions tesseraJdbcOptions;

    private OrionKeyHelper orionKeyHelper;

    private Encryptor tesseraEncryptor = EncryptorFactory.newFactory("NACL").create();

    private InboundDbHelper inboundDbHelper;

    private ObjectMapper cborObjectMapper = JacksonObjectMapperFactory.create();

    static EntityManagerFactory createEntityManagerFactory(TesseraJdbcOptions jdbcOptions) {
        Map<String,String> jdbcProperties = new HashMap<>();
        jdbcProperties.put("javax.persistence.jdbc.user", jdbcOptions.getUsername());
        jdbcProperties.put("javax.persistence.jdbc.password", jdbcOptions.getPassword());
        jdbcProperties.put("javax.persistence.jdbc.url", jdbcOptions.getUrl());

        jdbcProperties.put("eclipselink.logging.logger", "org.eclipse.persistence.logging.slf4j.SLF4JLogger");
        jdbcProperties.put("eclipselink.logging.level", "FINE");
        jdbcProperties.put("eclipselink.logging.parameters", "true");
        jdbcProperties.put("eclipselink.logging.level.sql", "FINE");

        jdbcProperties.put("eclipselink.connection-pool.initial","20");
        jdbcProperties.put("eclipselink.connection-pool.min","20");
        jdbcProperties.put("eclipselink.connection-pool.max","20");

        jdbcProperties.put("javax.persistence.schema-generation.database.action", jdbcOptions.getAction());

        return Persistence.createEntityManagerFactory("tessera-em", jdbcProperties);
    }

    public MigrateDataCommand(
            InboundDbHelper inboundDbHelper,
            TesseraJdbcOptions tesseraJdbcOptions,
            OrionKeyHelper orionKeyHelper) {

        this.inboundDbHelper = inboundDbHelper;
        this.tesseraJdbcOptions = tesseraJdbcOptions;
        this.orionKeyHelper = orionKeyHelper;
    }

    @Override
    public Boolean call() throws Exception {

        Disruptor<OrionDataEvent> disruptor =
                new Disruptor<>(
                    OrionDataEvent.FACTORY,
                        16,
                        (ThreadFactory) Thread::new,
                        ProducerType.SINGLE,
                        new BlockingWaitStrategy());

        InputType inputType = inboundDbHelper.getInputType();
        final LevelDbDataProducer inboundAdapter;
        EncryptorHelper encryptorHelper = new EncryptorHelper(tesseraEncryptor);
        EncryptedKeyMatcher encryptedKeyMatcher = new EncryptedKeyMatcher(orionKeyHelper,encryptorHelper);
        RecipientBoxHelper recipientBoxHelper = new RecipientBoxHelper(orionKeyHelper);

        switch (inputType) {
            case LEVELDB:
                new LeveldbMigrationInfoFactory().from(inboundDbHelper.getLevelDb().get());
                inboundAdapter =
                        new LevelDbDataProducer(inboundDbHelper.getLevelDb().get(), disruptor);
                break;
//            case JDBC:
//                DataSource dataSource = inboundDbHelper.getJdbcDataSource().get();
//                inboundAdapter = new JdbcOrionDataAdapter(dataSource, cborObjectMapper, disruptor,encryptedKeyMatcher,recipientBoxHelper);
//                break;
            default:
                throw new UnsupportedOperationException("");
        }

        EntityManagerFactory entityManagerFactory = createEntityManagerFactory(tesseraJdbcOptions);

        List<EventHandler<OrionDataEvent>> handlers = new ArrayList<>();

        Disruptor<TesseraDataEvent> tesseraDataEventDisruptor = new Disruptor<TesseraDataEvent>(TesseraDataEvent.FACTORY,
            16,
            (ThreadFactory) Thread::new,
            ProducerType.SINGLE,
            new BlockingWaitStrategy());

        for(int i = 0;i < 1;i++) {
            handlers.add(new ConvertToPrivacyGroupEntity(tesseraDataEventDisruptor));
            handlers.add(new ConvertToTransactionEntity(tesseraDataEventDisruptor));
        }

        CompletionHandler completionHandler = new CompletionHandler();

        disruptor
                .handleEventsWith(handlers.toArray(EventHandler[]::new))
                    .then(completionHandler);

        disruptor.setDefaultExceptionHandler(new ExceptionHandler<OrionDataEvent>() {
            @Override
            public void handleEventException(Throwable ex, long sequence, OrionDataEvent event) {
                LOGGER.error("Unable to process event {}",event);
                LOGGER.error(null,ex);
                disruptor.shutdown();
            }

            @Override
            public void handleOnStartException(Throwable ex) {
                LOGGER.error(null,ex);
            }

            @Override
            public void handleOnShutdownException(Throwable ex) {
                LOGGER.error(null,ex);
            }
        });

        disruptor.start();

        inboundAdapter.start();

        completionHandler.await();
        LOGGER.info("DONE");

        disruptor.shutdown();

        return Boolean.TRUE;
    }
}
