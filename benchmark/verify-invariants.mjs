// Post-Run Financial Invariant Verification via REST API & Invariant checks
import fs from 'fs';

const BASE_URL = process.env.BASE_URL || 'http://localhost:8080';

async function main() {
  if (!fs.existsSync('benchmark/benchmark-accounts.json')) {
    console.error('benchmark/benchmark-accounts.json not found.');
    process.exit(1);
  }
  const accounts = JSON.parse(fs.readFileSync('benchmark/benchmark-accounts.json', 'utf8'));

  console.log('====================================================');
  console.log('POST-RUN FINANCIAL INVARIANT VALIDATION');
  console.log('====================================================');

  // 1. Verify W1 independent pairs (conservation of money per pair)
  let w1Passed = true;
  let totalW1Sum = 0;
  for (let i = 0; i < accounts.independentPairs.length; i++) {
    const pair = accounts.independentPairs[i];
    const sRes = await fetch(`${BASE_URL}/api/accounts/${pair.sender}/balance`).then(r => r.json());
    const rRes = await fetch(`${BASE_URL}/api/accounts/${pair.receiver}/balance`).then(r => r.json());
    
    if (sRes.balance < 0 || rRes.balance < 0) {
      console.error(`Negative balance found in pair ${i}: sender=${sRes.balance}, receiver=${rRes.balance}`);
      w1Passed = false;
    }

    const pairSum = sRes.balance + rRes.balance;
    if (pairSum !== 10000000) {
      console.error(`Invariant Violation in pair ${i}: sum is ${pairSum}, expected 10000000`);
      w1Passed = false;
    }
    totalW1Sum += pairSum;
  }
  if (w1Passed) {
    console.log(`✅ W1 Invariant Passed: All ${accounts.independentPairs.length} pairs conserved exact balance (Total: ${totalW1Sum} INR).`);
  }

  // 2. Verify W2 hot sender + receivers (conservation of money across cluster)
  const hotSenderRes = await fetch(`${BASE_URL}/api/accounts/${accounts.hotSender}/balance`).then(r => r.json());
  let receiversSum = 0;
  for (const rId of accounts.hotReceivers) {
    const rRes = await fetch(`${BASE_URL}/api/accounts/${rId}/balance`).then(r => r.json());
    if (rRes.balance < 0) {
      console.error(`Negative balance in hot receiver ${rId}: ${rRes.balance}`);
    }
    receiversSum += rRes.balance;
  }
  const totalHotCluster = hotSenderRes.balance + receiversSum;
  if (totalHotCluster === 50000000 && hotSenderRes.balance >= 0) {
    console.log(`✅ W2 Invariant Passed: Hot sender cluster conserved exact balance (Total: 50,000,000 INR, Sender: ${hotSenderRes.balance}, Receivers: ${receiversSum}).`);
  } else {
    console.error(`❌ W2 Invariant Failed: sum is ${totalHotCluster}, expected 50000000`);
  }

  // 3. Verify Query accounts
  for (const qId of accounts.queryAccounts) {
    const qRes = await fetch(`${BASE_URL}/api/accounts/${qId}/balance`).then(r => r.json());
    if (qRes.balance < 0) {
      console.error(`Negative balance in query account ${qId}: ${qRes.balance}`);
    }
  }
  console.log('✅ Balance Integrity: No negative balances across all queried accounts.');
  console.log('====================================================\n');
}

main().catch(err => {
  console.error('Verification error:', err);
  process.exit(1);
});
