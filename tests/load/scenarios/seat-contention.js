import { optionsFor } from '../config.js';
import { item, spec, call } from '../helpers/http.js';
export { handleSummary } from '../helpers/http.js';
export const options=optionsFor();
export default function(){const w=item();call(spec(`/ticket-orders/${w.orderId}/pay`,w.token,null,'S03 same seat'),['SEAT_NOT_AVAILABLE','INSUFFICIENT_INVENTORY'],b=>b.status==='PAID' && b.tickets.length===1);}
