import { optionsFor, fixture } from '../config.js';
import { spec, call } from '../helpers/http.js';
export { handleSummary } from '../helpers/http.js';
export const options=optionsFor();
export default function(){call(spec(`/publisher/sessions/${fixture.sessionId}/cancel`,fixture.publisherToken,{requestId:'load-cancel',reason:'Isolated cancellation load'},'S09 cancel'),[],b=>Boolean(b.eventId));}
