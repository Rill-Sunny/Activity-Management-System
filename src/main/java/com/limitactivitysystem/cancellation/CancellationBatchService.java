package com.limitactivitysystem.cancellation;

import com.limitactivitysystem.messaging.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.util.*;

@Service
public class CancellationBatchService {
    public static final String CONSUMER="cancellation-generation-v1";
    private final CancellationMapper mapper; private final ConsumerProcessMapper processed; private final ConsumerFailureMapper failures;
    public CancellationBatchService(CancellationMapper mapper,ConsumerProcessMapper processed,ConsumerFailureMapper failures) {
        this.mapper=mapper;this.processed=processed;this.failures=failures;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void receive(String messageId,long eventId) {
        var batch=mapper.batchForEvent(eventId);
        if(batch==null)throw CancellationService.missing();
        if(processed.begin(CONSUMER,messageId)==1) generate(batch.batchId());
        failures.delete(CONSUMER,messageId);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public boolean generate(long batchId) {
        var batch=mapper.lockBatch(batchId);
        if(batch==null||batch.scanPhase()==4)return false;
        var page=mapper.page(batch.eventId(),batch.scanPhase(),batch.nextCursor());
        String type=List.of("ORDER","TICKET","RESERVATION","GIFT").get(batch.scanPhase());
        String action=List.of("CLOSE","REFUND","INVALIDATE","INVALIDATE").get(batch.scanPhase());
        for(var row:page) mapper.insertItem(batchId,type,row.targetId(),row.sessionId(),action);
        boolean last=page.size()<50;
        long cursor=last?0:page.get(page.size()-1).targetId();
        mapper.checkpoint(batchId,batch.scanPhase()+(last?1:0),cursor,page.size());
        summarize(batchId);
        return batch.scanPhase()+(last?1:0)<4;
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void summarize(long id) {
        var batch=mapper.lockBatch(id); if(batch==null)return;
        var counts=mapper.counts(id);
        int total=((Number)counts.get("total")).intValue(), success=((Number)counts.get("succeeded")).intValue(), failed=((Number)counts.get("failed")).intValue();
        String status=batch.scanPhase()==4&&total==success?"COMPLETED":failed>0?"PARTIAL_FAILED":"PROCESSING";
        mapper.summarize(id,status,total,success,failed);
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public CancellationMapper.Item claim() {
        var item=mapper.next(); if(item==null)return null;
        if(item.retryCount()>=5) {
            mapper.fail(item.itemId(),item.claimToken(),"FAILED","Lease expired after maximum attempts; explicit retry required");
            return item;
        }
        mapper.claim(item.itemId(),UUID.randomUUID().toString());return mapper.lockItem(item.itemId());
    }
    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public void failed(CancellationMapper.Item item,Exception error) {
        // Exception messages can contain SQL/credentials. Store a safe, searchable type; log correlation uses item ID.
        Throwable root=error;
        while(root.getCause()!=null && root.getCause()!=root)root=root.getCause();
        String reason="Processing failed: "+root.getClass().getSimpleName()+"; item="+item.itemId();
        if(root instanceof java.sql.SQLException sql)reason+="; SQLState="+sql.getSQLState()+"; vendorCode="+sql.getErrorCode();
        mapper.fail(item.itemId(),item.claimToken(),item.retryCount()>=5?"FAILED":"RETRY_PENDING",
                reason);
    }
}
