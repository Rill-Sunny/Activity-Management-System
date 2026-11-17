"""Generate the executable HTTP demo and the equivalent Postman 2.1 import file."""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[3]
steps = []
def var(name): return '{{' + name + '}}'
def call(name, path, actor='publisherA', body=None, status=200, capture=None, check=None, method='POST', wait=0):
    step = dict(name=name, method=method, path=path, actor=actor, expectedStatus=status)
    if body is not None: step['body'] = body
    if capture: step['capture'] = capture
    if check: step['check'] = check
    if wait: step['waitSeconds'] = wait
    steps.append(step)
def sql(name, query, expected='0', wait=60):
    steps.append(dict(name=name, sqlCheck=query, expected=expected, waitSeconds=wait))
def activity(key, kind='PAID', close='closeTime'):
    call('Create '+key, '/publisher/activities', body=dict(name='Demo '+key, description='Release HTTP demo', type=kind, openTime=var('openTime'),closeTime=var(close)),status=201,capture={key:'/activityId'})
def session(activity_key,key,mode='AUTO_ASSIGN',capacity=4):
    body=dict(startTime=var('startTime'),endTime=var('endTime'),capacity=capacity)
    if mode: body.update(maxTicketsPerUser=min(4,capacity),seatMode=mode)
    call('Create '+key, '/publisher/activities/'+var(activity_key)+'/sessions',body=body,status=201,capture={key:'/activitySessionId'})
def zone(session_key,key,kind='PAID',count=4):
    call('Create '+key,'/publisher/sessions/'+var(session_key)+'/zones',body=dict(name=key,zoneType=kind,price=100 if kind=='PAID' else 0,totalAmount=count),status=201,capture={key:'/zoneId'})
    call('Create seats '+key,'/publisher/zones/'+var(key)+'/seats',body={'seats':[dict(rowNo=1,seatNo=i,allocationPriority=i) for i in range(1,count+1)]},status=201,capture={key+'Seat'+str(i):'/seatIds/'+str(i-1) for i in range(1,count+1)})
def publish(key): call('Publish '+key,'/publisher/activities/'+var(key)+'/publish',status=204)
def ticket(key,session_key,zone_key,actor='buyerA',count=1,selected=False,pay=True,seat_start=1):
    items=[dict(zoneId=var(zone_key),allocationMode='AUTO_ASSIGN',quantity=count,acceptNonAdjacent=True)]
    if selected: items=[dict(zoneId=var(zone_key),allocationMode='SELECT_SEAT',requestedSeatId=var(zone_key+'Seat'+str(i)),quantity=1,acceptNonAdjacent=False) for i in range(seat_start,seat_start+count)]
    call('Create '+key,'/ticket-orders',actor,dict(requestId=var('prefix')+'-'+key,sessionId=var(session_key),items=items),201,{key:'/orderId'})
    if pay: call('Pay '+key,'/ticket-orders/'+var(key)+'/pay',actor,capture={key+'Ticket'+str(i):'/tickets/'+str(i-1)+'/ticketId' for i in range(1,count+1)},check={'/status':'PAID'})
def refund(key,order,ticket_key,actor='buyerA'):
    call('Apply '+key,'/ticket-refunds',actor,dict(requestId=var('prefix')+'-'+key,orderId=var(order),ticketIds=[var(ticket_key)],reason='Release per-ticket refund'),201,{key:'/refundId'})
    call('Process '+key,'/ticket-refunds/'+var(key)+'/process',actor,check={'/status':'SUCCEEDED'})

call('Authenticated buyer','/users/me','buyerA',method='GET',check={'/role':'USER'})
call('Unauthenticated access rejected','/users/me','',status=401,method='GET')
call('Buyer cannot publish','/publisher/activities','buyerA',{},403)
# A real short window, without changing clocks, stored deadlines or successful business records.
steps.append({'name':'Set short retention times','setTimes':{'retentionClose':75,'retentionStart':150,'retentionEnd':750}})
activity('retentionActivity',close='retentionClose');session('retentionActivity','retentionSession',capacity=2);zone('retentionSession','retentionZone',count=2);publish('retentionActivity')
ticket('retentionOrder','retentionSession','retentionZone','buyerC')
call('Short reschedule window','/publisher/sessions/'+var('retentionSession')+'/reschedules',body=dict(requestId=var('prefix')+'-retain',newStartTime=var('retentionStartOffset'),newEndTime=var('retentionEndOffset'),reason='Real automatic-retention deadline'),status=201,capture={'retentionEvent':'/eventId'})

activity('activityA');session('activityA','selectSession','SELECT_SEAT',6);zone('selectSession','paidZone');zone('selectSession','giftZone','GIFT',2)
session('activityA','autoSession');zone('autoSession','autoZone');publish('activityA')
call('Other publisher denied','/publisher/activities/'+var('activityA')+'/sessions','publisherB',dict(startTime=var('startTime'),endTime=var('endTime'),capacity=1,maxTicketsPerUser=1,seatMode='AUTO_ASSIGN'),403)
call('Enable activity whitelist','/publisher/activities/'+var('activityA')+'/whitelist',method='PUT')
for actor in ['buyerA','buyerB','buyerC']:
    call('Whitelist '+actor,'/publisher/activities/'+var('activityA')+'/whitelist/members',body={'userId':var(actor+'_id')},status=201)
call('Allocate GIFT seats','/publisher/gift-zones/'+var('giftZone')+'/allocations',body=dict(requestId=var('prefix')+'-gift',quantity=1),status=201,capture={'giftOperation':'/operationId'})
call('Repeat GIFT allocation','/publisher/gift-zones/'+var('giftZone')+'/allocations',body=dict(requestId=var('prefix')+'-gift',quantity=1),capture={'giftReplay':'/operationId'})
ticket('selectedOrder','selectSession','paidZone',count=2,selected=True)
call('Payment replay on second instance','/ticket-orders/'+var('selectedOrder')+'/pay','buyerA',check={'/status':'PAID'})
steps[-1]['instance']='B'
ticket('autoOrder','autoSession','autoZone','buyerB',2)
ticket('pendingOrder','selectSession','paidZone','buyerB',pay=False,selected=True,seat_start=3)
activity('activityB','FREE');session('activityB','freeSession',None,2);publish('activityB')
call('FREE reservation','/sessions/'+var('freeSession')+'/reservations','buyerA',status=201,capture={'freeReservation':'/reservationId'})
call('Cancel FREE reservation','/reservations/'+var('freeReservation')+'/cancel','buyerA')
call('Rebook FREE reservation','/sessions/'+var('freeSession')+'/reservations','buyerA',status=201,capture={'freeReservation':'/reservationId'})

for key,qualification,policy in [('publicProduct','PUBLIC','STANDARD_7D'),('limitedProduct','TICKET_REQUIRED','SPECIAL_NO_REASON_RETURN')]:
    body=dict(activityId=var('activityA'),name=key,qualificationType=qualification,purchaseLimit=2,refundPolicy=policy)
    if qualification=='TICKET_REQUIRED': body.update(qualificationScope='SESSION',qualificationSessionId=var('selectSession'))
    call('Create '+key,'/publisher/products',body=body,status=201,capture={key:'/productId'})
    for suffix,stock in [('BlackM',0 if qualification=='TICKET_REQUIRED' else 3),('WhiteL',3)]:
        call('Create SKU '+key+suffix,'/publisher/products/'+var(key)+'/skus',body=dict(name=suffix,price=39.90,stock=stock),status=201,capture={key+suffix:'/skuId'})
    call('Publish '+key,'/publisher/products/'+var(key)+'/publish')
call('Out-of-stock SKU stays selected','/product-orders/preview','buyerA',{'items':[dict(skuId=var('limitedProductBlackM'),quantity=1)]},check={'/available':False})
mixed=[dict(skuId=var('publicProductBlackM'),quantity=1),dict(skuId=var('publicProductWhiteL'),quantity=1),dict(skuId=var('limitedProductWhiteL'),quantity=1,acknowledgeSpecialPolicy=True)]
call('Explicit adjusted mixed order','/product-orders/confirm','buyerA',dict(requestId=var('prefix')+'-mixed',items=mixed),201,{'mixedOrder':'/orderId'})
call('Atomic multi-SKU payment','/product-orders/'+var('mixedOrder')+'/pay','buyerA',capture={'publicLineA':'/items/0/orderItemId','publicLineB':'/items/1/orderItemId','limitedLine':'/items/2/orderItemId'},check={'/status':'PAID'})
call('Shared Product limit reached','/product-orders/confirm','buyerA',dict(requestId=var('prefix')+'-over-limit',items=[dict(skuId=var('publicProductWhiteL'),quantity=1)]),409)
call('Ship one line','/publisher/product-orders/'+var('mixedOrder')+'/ship',body=dict(orderItemId=var('publicLineA'),quantity=1))
call('Receive shipped line','/product-orders/'+var('mixedOrder')+'/receive','buyerA',dict(orderItemId=var('publicLineA'),quantity=1))
call('Partial refund without illegal restock','/product-after-sales','buyerA',dict(requestId=var('prefix')+'-partial',orderId=var('mixedOrder'),items=[dict(orderItemId=var('publicLineA'),quantity=1,reason='QUALITY_PROBLEM',simulateInspectionPassed=True,simulateResalable=False)]),201,check={'/status':'SUCCEEDED','/items/0/resalable':False})
sql('Non-resalable SKU stays deducted','SELECT remains_stock FROM sku WHERE sku_id={{publicProductBlackM}};', '2')
refund('firstRefund','selectedOrder','selectedOrderTicket1')
sql('First qualification event consumed',"SELECT COUNT(*) FROM outbox_event e JOIN consumer_process p ON p.event_id=e.event_id WHERE e.event_type='TICKET_QUALIFICATION_CHANGED' AND e.aggregate_id={{firstRefund}};",'1')
sql('Another valid ticket prevents linked after-sale','SELECT COUNT(*) FROM ticket_qualification_after_sale_task WHERE product_order_item_id={{limitedLine}};')
refund('lastRefund','selectedOrder','selectedOrderTicket2')
sql('Last ticket triggers actual linked refund',"SELECT COUNT(*) FROM ticket_qualification_after_sale_task WHERE product_order_item_id={{limitedLine}} AND status='SUCCEEDED';",'1',120)
sql('Public line remains isolated','SELECT refunded_quantity FROM product_order_item WHERE order_item_id={{publicLineB}};')
call('Reschedule paid auto session','/publisher/sessions/'+var('autoSession')+'/reschedules',body=dict(requestId=var('prefix')+'-reschedule',newStartTime=var('newStartOffset'),newEndTime=var('newEndOffset'),reason='Release venue change'),status=201,capture={'rescheduleEvent':'/eventId'})
call('Keep one ticket','/reschedules/'+var('rescheduleEvent')+'/confirm','buyerB',{'ticketIds':[var('autoOrderTicket2')]})
call('Special zero-fee refund','/reschedules/'+var('rescheduleEvent')+'/special-refunds','buyerB',dict(requestId=var('prefix')+'-special',ticketIds=[var('autoOrderTicket1')]),201,{'specialRefund':'/refunds/0/refundId'})
call('Process special refund','/ticket-refunds/'+var('specialRefund')+'/process','buyerB',check={'/items/0/feeRate':0})
call('Cancel selected session','/publisher/sessions/'+var('selectSession')+'/cancel',body=dict(requestId=var('prefix')+'-cancel-session',reason='Release session cancellation'),status=202,capture={'sessionCancellation':'/eventId'})
call('Cancelled session rejects pending payment','/ticket-orders/'+var('pendingOrder')+'/pay','buyerB',status=409)
call('Wait selected-session batch','/publisher/cancellations/'+var('sessionCancellation'),method='GET',check={'/batch/status':'COMPLETED'},wait=150)
sql('Independent session ticket remains valid',"SELECT COUNT(*) FROM ticket WHERE ticket_id={{autoOrderTicket2}} AND status='VALID';",'1')
call('Cancel FREE activity','/publisher/activities/'+var('activityB')+'/cancel',body=dict(requestId=var('prefix')+'-cancel-free',reason='Release activity cancellation'),status=202,capture={'freeCancellation':'/eventId'})
call('Wait FREE batch','/publisher/cancellations/'+var('freeCancellation'),method='GET',check={'/batch/status':'COMPLETED'},wait=150)
call('Reservation no longer effective','/reservations/'+var('freeReservation')+'/participation','buyerA',method='GET',check={'/effective':False})
call('Cancel remaining paid activity','/publisher/activities/'+var('activityA')+'/cancel',body=dict(requestId=var('prefix')+'-cancel-paid',reason='Release final cancellation'),status=202,capture={'paidCancellation':'/eventId'})
call('Wait remaining paid batch','/publisher/cancellations/'+var('paidCancellation'),method='GET',check={'/batch/status':'COMPLETED'},wait=150)
call('Automatic retention after actual deadline','/reschedules/'+var('retentionEvent'),'buyerC',method='GET',check={'/tickets/0/status':'AUTO_RETAINED'},wait=200)
call('Read real notifications','/notifications','buyerA',method='GET')
call('Cache first read','/products/'+var('publicProduct'),' ',method='GET')
call('Cache repeated read','/products/'+var('publicProduct'),' ',method='GET')
# Cancelled parent products may be hidden; use the retained activity for stable public cache reads.
steps[-2]['path']=steps[-1]['path']='/activities/'+var('retentionActivity')
steps[-2]['actor']=steps[-1]['actor']=''

plan={'version':1,'description':'All business transitions use HTTP. SQL entries only verify committed facts. Role bootstrap is separate.','steps':steps}
(ROOT/'tests/fixtures/demo/plan.json').write_text(json.dumps(plan,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
items=[]
pending_times={}
for step in steps:
    if 'setTimes' in step: pending_times=step['setTimes']
    if 'method' not in step: continue
    auth={'type':'bearer','bearer':[{'key':'token','value':var(step['actor']+'_token'),'type':'string'}]} if step['actor'] else {'type':'noauth'}
    url=var('baseUrlB' if step.get('instance')=='B' else 'baseUrl')+step['path']
    request={'method':step['method'],'header':[],'url':url,'auth':auth,'description':'Expected HTTP '+str(step['expectedStatus'])+(('; poll until '+str(step.get('check'))) if step.get('waitSeconds') else '')}
    if 'body' in step:
        raw=json.dumps(step['body'],ensure_ascii=False,indent=2)
        # IDs captured from previous JSON responses are JSON numbers, not quoted strings.
        raw=re.sub(r'"(\{\{[A-Za-z0-9_]+\}\})"',lambda m:m[1] if any(m[1]==var(k) for s in steps for k in s.get('capture',{})) or m[1].endswith('_id}}') else m[0],raw)
        request.update(header=[{'key':'Content-Type','value':'application/json'}],body={'mode':'raw','raw':raw,'options':{'raw':{'language':'json'}}})
    script=[f"pm.test('HTTP {step['expectedStatus']}', function () {{ pm.response.to.have.status({step['expectedStatus']}); }});"]
    if step.get('capture') or step.get('check'):
        script+=['const body = pm.response.json();',"const pointer = p => p.split('/').slice(1).reduce((v,k) => v[k], body);"]
        for key,path in step.get('capture',{}).items(): script.append('pm.collectionVariables.set('+json.dumps(key)+', pointer('+json.dumps(path)+'));')
        for path,value in step.get('check',{}).items(): script.append('pm.test('+json.dumps(path)+', function () { pm.expect(pointer('+json.dumps(path)+')).to.eql('+json.dumps(value)+'); });')
    events=[{'listen':'test','script':{'type':'text/javascript','exec':script}}]
    if pending_times:
        setup=['const times = '+json.dumps(pending_times)+';', 'Object.entries(times).forEach(([name, seconds]) => {', '  const local = new Date(Date.now() + seconds * 1000 + 8 * 3600000).toISOString().slice(0, -1);', "  pm.collectionVariables.set(name, local);", "  pm.collectionVariables.set(name + 'Offset', local + '+08:00');", '});']
        events.insert(0,{'listen':'prerequest','script':{'type':'text/javascript','exec':setup}})
        pending_times={}
    items.append({'name':step['name'],'request':request,'event':events})
collection={'info':{'name':'LAS 1.0.0 executable demo','schema':'https://schema.getpostman.com/json/collection/v2.1.0/collection.json','description':'Import as Postman 2.1 in Apifox. Prepare accounts and variables with tests/release/run-demo.ps1 -PrepareOnly. Wait/poll async steps as described; the PowerShell runner performs all SQL checks and waits automatically.'},'variable':[{'key':'baseUrl','value':'http://127.0.0.1:8081'}],'item':items}
(ROOT/'docs/api/apifox-collection.json').write_text(json.dumps(collection,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
print(f'{len(steps)} demo steps; {len(items)} HTTP requests')
