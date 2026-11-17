import { optionsFor, fixture } from '../config.js';
import { item, spec, pair } from '../helpers/http.js';
export { handleSummary } from '../helpers/http.js';
export const options=optionsFor(__ENV.MODE!=='cold');
export default function(){const w=item();pair([spec(`/activities/${fixture.activityId}`,w.token,null,'S08 Activity',0,'GET'),spec(`/products/${fixture.productId}`,w.token,null,'S08 Product',1,'GET')],[],b=>Boolean(b.activityId || b.productId));}
