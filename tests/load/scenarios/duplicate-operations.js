import { optionsFor } from '../config.js';
import { item, spec, call } from '../helpers/http.js';
export { handleSummary } from '../helpers/http.js';
export const options=optionsFor();
export default function(){const w=item();call(spec(w.path,w.token,null,'S07 duplicate '+w.operation),['REQUEST_IN_PROGRESS'],b=>['PAID','SUCCEEDED'].includes(b.status));}
