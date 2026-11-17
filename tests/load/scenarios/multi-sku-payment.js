import { optionsFor } from '../config.js';
import { item, spec, call, businessChecks } from '../helpers/http.js';
export { handleSummary } from '../helpers/http.js';
export const options=optionsFor();
export default function(){const w=item();const b=call(spec(`/product-orders/${w.orderId}/pay`,w.token,null,'S05 atomic multi SKU'),['PRODUCT_ORDER_OUT_OF_STOCK']);businessChecks.add(b.code==='PRODUCT_ORDER_OUT_OF_STOCK');}
