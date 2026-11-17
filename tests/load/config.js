import { SharedArray } from 'k6/data';
export const fixture = JSON.parse(open(__ENV.FIXTURE_FILE || '/results/fixture.private.json'));
export const work = new SharedArray('work', () => fixture.work);
export const bases = [__ENV.BASE_URL || 'http://app-a:8080', __ENV.BASE_URL_B || 'http://app-b:8080'];
export function optionsFor(steady = false) {
  const vus = Number(__ENV.VUS || 10);
  const scenarios = steady ? {
    warmup: { executor: 'constant-vus', vus, duration: __ENV.WARMUP || '120s', gracefulStop: '5s' },
    measured: { executor: 'constant-vus', vus, duration: __ENV.DURATION || '300s', startTime: __ENV.WARMUP || '120s', gracefulStop: '15s' },
  } : { measured: { executor: 'shared-iterations', vus: Math.min(vus, work.length), iterations: work.length, maxDuration: '180s' } };
  return { scenarios, discardResponseBodies: false, summaryTrendStats: ['avg','min','med','p(50)','p(95)','p(99)','max','count'],
    systemTags: ['status','method','name','scenario','expected_response'],
    thresholds: { technical_errors: ['rate<0.01'], business_checks: ['rate==1'] } };
}
