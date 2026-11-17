import http from 'k6/http';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';
import { bases, work } from '../config.js';
export const technicalErrors = new Rate('technical_errors');
export const businessChecks = new Rate('business_checks');
const accepted = new Counter('accepted_responses');
const refused = new Counter('expected_refusals');
const requests = new Counter('measured_requests');
const latency = new Trend('measured_latency', true);
const successfulLatency = new Trend('successful_latency', true);
export function item() { return work[exec.scenario.iterationInTest % work.length]; }
export function spec(path, token, body, name, offset=0, method='POST') {
  return { method, url: bases[(exec.scenario.iterationInTest+offset)%2]+path,
    body: body == null ? null : JSON.stringify(body), params: { timeout: '25s',
      headers: { Authorization: 'Bearer '+token, 'Content-Type': 'application/json', 'X-Request-ID': `load-${__ENV.RUN_ID || 'round'}-${exec.vu.idInTest}-${exec.vu.iterationInScenario}-${offset}` },
      tags: { name }, responseCallback: http.expectedStatuses({ min:200,max:299 },409) } };
}
export function classify(response, expectedCodes=[], predicate=null) {
  let body={};try{body=response.json();}catch(_){}
  const success=response.status>=200 && response.status<300;
  const expected=response.status===409 && expectedCodes.includes(body.code);
  const valid=(success || expected) && (!success || !predicate || predicate(body));
  if(exec.scenario.name==='measured') {
    requests.add(1); latency.add(response.timings.duration);
    technicalErrors.add(!success && !expected); businessChecks.add(valid);
    if(success){accepted.add(1);successfulLatency.add(response.timings.duration);}
    if(expected){refused.add(1,{code:body.code});}
  }
  if(!valid) console.error(JSON.stringify({name:response.request.url.replace(/\d+/g,':id'),status:response.status,code:body.code || response.error_code || 'INVALID_BODY'}));
  return body;
}
export function call(request,codes=[],predicate=null) {
  return classify(http.request(request.method,request.url,request.body,request.params),codes,predicate);
}
export function pair(requests,codes=[],predicate=null) { return http.batch(requests).map(r=>classify(r,codes,predicate)); }
export function handleSummary(data) {
  return { '/results/k6-summary.json': JSON.stringify(data,null,2), stdout: JSON.stringify({scenario:__ENV.SCENARIO,vus:__ENV.VUS,metrics:data.metrics})+'\n' };
}
