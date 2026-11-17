import { optionsFor } from '../config.js';
import { item, spec, pair } from '../helpers/http.js';
export { handleSummary } from '../helpers/http.js';
export const options=optionsFor();
export default function(){const w=item();pair(w.orders.map((id,i)=>spec(`/ticket-orders/${id}/pay`,w.token,null,'S04 quota',i)),['PURCHASE_LIMIT_EXCEEDED'],b=>b.status==='PAID' && b.tickets.length===3);}
