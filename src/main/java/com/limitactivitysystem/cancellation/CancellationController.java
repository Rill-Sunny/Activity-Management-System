package com.limitactivitysystem.cancellation;
import com.limitactivitysystem.security.CurrentUserProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import java.util.*;

@RestController
public class CancellationController {
    private final CancellationService service;private final CancellationMapper mapper;private final CancellationResourceMapper resources;
    private final CurrentUserProvider current;
    public CancellationController(CancellationService service,CancellationMapper mapper,CancellationResourceMapper resources,CurrentUserProvider current){this.service=service;this.mapper=mapper;this.resources=resources;this.current=current;}
    @PostMapping("/publisher/sessions/{id}/cancel") @ResponseStatus(HttpStatus.ACCEPTED)
    public CancellationMapper.Event session(@PathVariable @Positive long id,@RequestBody @Valid CancellationService.Request request){return service.cancel(false,id,request);}
    @PostMapping("/publisher/activities/{id}/cancel") @ResponseStatus(HttpStatus.ACCEPTED)
    public CancellationMapper.Event activity(@PathVariable @Positive long id,@RequestBody @Valid CancellationService.Request request){return service.cancel(true,id,request);}
    @GetMapping("/publisher/cancellations/{id}")
    public Map<String,Object> detail(@PathVariable @Positive long id,@RequestParam(defaultValue="0") @PositiveOrZero long afterItemId){return service.detail(id,afterItemId);}
    @PostMapping("/publisher/cancellations/{id}/items/{item}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public void retry(@PathVariable @Positive long id,@PathVariable @Positive long item){service.retry(id,item);}
    @GetMapping("/sessions/{id}/cancellation")
    public Map<String,Object> sessionNotice(@PathVariable @Positive long id){var result=mapper.sessionNotice(id);return result==null?Map.of():result;}
    @GetMapping("/activities/{id}/cancellation")
    public Map<String,Object> activityNotice(@PathVariable @Positive long id){var result=mapper.activityNotice(id);return result==null?Map.of():result;}
    @GetMapping("/reservations/{id}/participation")
    public Map<String,Boolean> reservation(@PathVariable @Positive long id){var effective=resources.reservationEffective(id,current.getAuthenticatedUserId());if(effective==null)throw CancellationService.missing();return Map.of("effective",effective);}
    @GetMapping("/publisher/gift-items/{id}/participation")
    public Map<String,Boolean> gift(@PathVariable @Positive long id){var effective=resources.giftEffective(id,current.getAuthenticatedUserId());if(effective==null)throw CancellationService.missing();return Map.of("effective",effective);}
}
