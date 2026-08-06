package com.abdul.catalogo.masterdata.service;

import com.abdul.catalogo.synchronization.entity.SyncRecordEntity;
import com.abdul.catalogo.synchronization.repository.SyncRecordRepository;
import com.abdul.catalogo.synchronization.service.SyncEntityCatalog;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Comparator;
import java.util.List;

@Component
public class MasterDataProjectionRebuildRunner implements ApplicationRunner {
    private final SyncRecordRepository records;
    private final SyncEntityCatalog entityCatalog;
    private final RelationalMasterDataService masterData;
    private final ObjectMapper objectMapper;

    public MasterDataProjectionRebuildRunner(SyncRecordRepository records, SyncEntityCatalog entityCatalog,
                                             RelationalMasterDataService masterData, ObjectMapper objectMapper) {
        this.records = records;
        this.entityCatalog = entityCatalog;
        this.masterData = masterData;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<String> order = entityCatalog.dependencyOrder();
        List<SyncRecordEntity> masters = records.findAll().stream()
                .filter(record -> masterData.supports(record.getEntityType()))
                .filter(record -> record.getPayloadJson() != null && !record.getPayloadJson().isBlank()
                        && !record.getPayloadJson().trim().equals("{}"))
                .sorted(Comparator.comparingInt(record -> order.indexOf(record.getEntityType())))
                .toList();
        for (SyncRecordEntity record : masters) {
            JsonNode payload = read(record.getPayloadJson());
            masterData.apply(record.getEntityType(), record.getEntityId(), payload, record.getVersion(),
                    record.getOriginDeviceId(), record.isDeleted());
            if (record.getLastSequence() != null) {
                masterData.updateLastSequence(record.getEntityType(), record.getEntityId(), record.getLastSequence());
            }
            record.setPayloadJson("{}");
        }
        records.saveAll(masters);
    }

    private JsonNode read(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (JacksonException exception) {
            throw new IllegalStateException("No se pudo migrar un payload maestro de sync_records.", exception);
        }
    }
}
