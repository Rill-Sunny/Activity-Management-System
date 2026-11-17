import http from 'k6/http';
import { Rate, Counter } from 'k6/metrics';
const f=JSON.parse(open('/results/fixture.private.json'));
const bounded=new Rate('controlled_results');
const ok=new Counter('fallback_success');const busy=new Counter('bounded_rejection');
export const options={scenarios:{fault:{executor:'shared-iterations',vus:50,iterations:200,maxDuration:'60s'}},thresholds:{controlled_results:['rate==1']},summaryTrendStats:['p(50)','p(95)','p(99)','max']};
export default function(){
  const base=__ITER%2?'http://app-a:8080':'http://app-b:8080';
  for(const path of [`/activities/${f.activityId}`,`/products/${f.productId}`]){
    const r=http.get(base+path,{timeout:'10s',tags:{name:path.startsWith('/activities')?'Activity fallback':'Product fallback'}});
    let code='';try{code=r.json().code;}catch(_){}
    const rejected=r.status===503&&code==='CACHE_BACKEND_BUSY';
    bounded.add(r.status===200||rejected);if(r.status===200)ok.add(1);if(rejected)busy.add(1);
  }
}
export function handleSummary(data){return {'/results/redis-down-summary.json':JSON.stringify(data,null,2)};}
