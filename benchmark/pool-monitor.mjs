// HikariCP Real-Time Sampler
import fs from 'fs';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';
const DURATION_SEC = parseInt(process.argv[2] || '15', 10);
const OUTPUT_FILE = process.argv[3] || 'benchmark/pool-metrics.json';

async function fetchMetric(name) {
  try {
    const res = await fetch(`${BASE_URL}/actuator/metrics/${name}`);
    if (!res.ok) return 0;
    const data = await res.json();
    return data.measurements[0].value;
  } catch {
    return 0;
  }
}

async function main() {
  const maxPool = await fetchMetric('hikaricp.connections.max');
  console.log(`Starting pool monitor (Sampling for ${DURATION_SEC}s, maxPool=${maxPool})...`);

  let maxActive = 0;
  let maxPending = 0;
  let minIdle = maxPool;
  const start = Date.now();

  while (Date.now() - start < DURATION_SEC * 1000) {
    const [active, pending, idle] = await Promise.all([
      fetchMetric('hikaricp.connections.active'),
      fetchMetric('hikaricp.connections.pending'),
      fetchMetric('hikaricp.connections.idle')
    ]);

    if (active > maxActive) maxActive = active;
    if (pending > maxPending) maxPending = pending;
    if (idle < minIdle) minIdle = idle;

    await new Promise(r => setTimeout(r, 25));
  }

  const result = {
    maxPool,
    peakActive: maxActive,
    peakPending: maxPending,
    minIdle: minIdle
  };

  fs.writeFileSync(OUTPUT_FILE, JSON.stringify(result, null, 2));
  console.log('Monitor complete:', JSON.stringify(result));
}

main().catch(console.error);
