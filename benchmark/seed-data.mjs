// Enhanced Seed Data Generator for W1-W4 Baseline
import fs from 'fs';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function createAccount() {
  const res = await fetch(`${BASE_URL}/api/accounts`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ currency: 'INR' })
  });
  if (!res.ok) {
    throw new Error(`Failed to create account: ${res.status} ${await res.text()}`);
  }
  const data = await res.json();
  return data.id;
}

async function deposit(accountId, amount) {
  const res = await fetch(`${BASE_URL}/api/accounts/${accountId}/deposits`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ amount })
  });
  if (!res.ok) {
    throw new Error(`Failed to deposit: ${res.status} ${await res.text()}`);
  }
  return await res.json();
}

async function main() {
  console.log('====================================================');
  console.log('SEEDING TEST DATA FOR FULL PERFORMANCE BASELINE');
  console.log('Target:', BASE_URL);
  console.log('====================================================\n');

  // 1. W1: 100 Independent Pairs (200 accounts)
  const PAIR_COUNT = 100;
  console.log(`[1/3] Creating ${PAIR_COUNT} independent account pairs...`);
  const independentPairs = [];
  for (let i = 0; i < PAIR_COUNT; i++) {
    const sender = await createAccount();
    const receiver = await createAccount();
    await deposit(sender, 10000000); // 10,000,000 INR
    independentPairs.push({ sender, receiver });
    if ((i + 1) % 10 === 0) process.stdout.write(`.${i + 1}`);
  }
  console.log('\n  -> 100 independent pairs seeded.');

  // 2. W2: 1 Hot Sender + 50 Receivers
  console.log('\n[2/3] Creating 1 hot sender and 50 receivers...');
  const hotSender = await createAccount();
  await deposit(hotSender, 50000000); // 50,000,000 INR
  const hotReceivers = [];
  for (let i = 0; i < 50; i++) {
    const r = await createAccount();
    hotReceivers.push(r);
    if ((i + 1) % 10 === 0) process.stdout.write(`.${i + 1}`);
  }
  console.log('\n  -> Hot sender cluster seeded.');

  // 3. W3 & W4: 20 Query Accounts with 20 transactions each
  console.log('\n[3/3] Creating 20 query accounts with transaction history...');
  const queryAccounts = [];
  for (let i = 0; i < 20; i++) {
    const acc = await createAccount();
    await deposit(acc, 5000000);
    // Create 20 transfers per account to build real history
    for (let j = 0; j < 20; j++) {
      const counterparty = await createAccount();
      await fetch(`${BASE_URL}/api/transfers`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Idempotency-Key': crypto.randomUUID()
        },
        body: JSON.stringify({
          fromAccountId: acc,
          toAccountId: counterparty,
          amount: 10
        })
      });
    }
    queryAccounts.push(acc);
    process.stdout.write(`.${i + 1}`);
  }
  console.log('\n  -> Query accounts seeded.');

  const benchmarkData = {
    createdAt: new Date().toISOString(),
    independentPairs,
    hotSender,
    hotReceivers,
    queryAccounts
  };

  fs.writeFileSync('benchmark/benchmark-accounts.json', JSON.stringify(benchmarkData, null, 2));
  console.log('\n✅ All baseline test data generated and saved to benchmark/benchmark-accounts.json\n');
}

main().catch(err => {
  console.error('Fatal error during seeding:', err);
  process.exit(1);
});
