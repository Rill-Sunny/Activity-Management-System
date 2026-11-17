import { optionsFor, fixture } from '../config.js';
import { item, spec, call } from '../helpers/http.js';
export { handleSummary } from '../helpers/http.js';
export const options=optionsFor();
export default function(){const w=item();call(spec(`/sessions/${fixture.sessionId}/reservations`,w.token,null,'S01 reserve'),['RESERVATION_QUOTA_EXHAUSTED'],b=>b.reservationId>0);}
