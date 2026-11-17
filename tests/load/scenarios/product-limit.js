import { optionsFor } from '../config.js';
import { item, spec, pair } from '../helpers/http.js';
export { handleSummary } from '../helpers/http.js';
export const options=optionsFor();
export default function(){const w=item();pair(w.orders.map((id,i)=>spec(`/product-orders/${id}/pay`,w.token,null,'S06 Product quota',i)),['PRODUCT_PURCHASE_LIMIT_EXCEEDED'],b=>b.status==='PAID');}
