package com.limitactivitysystem.cancellation;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;

@Component
public class CancellationWorker {
    private final CancellationMapper mapper;private final CancellationBatchService batches;private final CancellationItemProcessor items;
    private final boolean enabled;
    public CancellationWorker(CancellationMapper mapper,CancellationBatchService batches,CancellationItemProcessor items,
            @Value("${cancellation.worker.enabled:true}") boolean enabled) {this.mapper=mapper;this.batches=batches;this.items=items;this.enabled=enabled;}
    @Scheduled(initialDelayString="${cancellation.worker.initial-delay:30000}",fixedDelayString="${cancellation.worker.delay:10000}")
    public void scheduled(){if(enabled)runOnce();}
    public void runOnce() {
        for(long id:mapper.unfinishedSummaries())batches.summarize(id);
        for(long id:mapper.activeBatches()) {
            try {
                // Bound one tick's work while advancing empty phases without another ten-second wait.
                // Calls cross the service proxy, so every page keeps its own committed checkpoint.
                for(int page=0;page<4;page++)if(!batches.generate(id))break;
            }catch(RuntimeException ex){LoggerFactory.getLogger(getClass()).warn("Cancellation page failed batch={} type={}",id,ex.getClass().getSimpleName());}
        }
        for(int n=0;n<50;n++) {
            var item=batches.claim();if(item==null)break;
            try {items.process(item);}catch(RuntimeException ex){batches.failed(item,ex);}
            batches.summarize(item.batchId());
        }
    }
}
