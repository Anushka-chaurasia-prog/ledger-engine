"use client";

import React, { useState, useEffect, useCallback, useMemo } from "react";
import { 
  LedgerApiClient, 
  Account, 
  TransactionHistoryItem, 
  formatINR, 
  generateUUID, 
  TransferResponse 
} from "@/lib/api";
import { Navbar } from "@/components/Navbar";
import { ArchitectureModal } from "@/components/ArchitectureModal";
import { IdempotencyModal } from "@/components/IdempotencyModal";
import { 
  Wallet, 
  PlusCircle, 
  ArrowRightLeft, 
  RefreshCw, 
  Sparkles, 
  Clock, 
  CheckCircle2, 
  ShieldCheck, 
  ArrowUpRight, 
  ArrowDownLeft, 
  Layers, 
  Zap, 
  Copy, 
  Check 
} from "lucide-react";

interface IdempotencyResultState {
  key: string;
  amountPaise: number;
  res1: TransferResponse | null;
  res2: TransferResponse | null;
  durationMs1: number;
  durationMs2: number;
  initialBalance: number;
  finalBalance: number;
}

export default function Home() {
  const [apiUrl, setApiUrl] = useState<string>("");
  const [healthStatus, setHealthStatus] = useState<"UP" | "DOWN" | "CHECKING">("CHECKING");
  const [dbType, setDbType] = useState<string>("PostgreSQL");
  
  // App Data
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [activeAccount, setActiveAccount] = useState<Account | null>(null);
  const [history, setHistory] = useState<TransactionHistoryItem[]>([]);
  
  // Transfer Form State
  const [fromId, setFromId] = useState<string>("");
  const [toId, setToId] = useState<string>("");
  const [amountRupees, setAmountRupees] = useState<string>("500");
  const [idempotencyKey, setIdempotencyKey] = useState<string>("");
  
  // Deposit Quick Action
  const [depositAmountRupees, setDepositAmountRupees] = useState<string>("1000");
  const [depositingAccountId, setDepositingAccountId] = useState<string | null>(null);

  // Loading States
  const [isLoading, setIsLoading] = useState<boolean>(false);
  const [isTransferring, setIsTransferring] = useState<boolean>(false);
  const [isSimulatingIdempotency, setIsSimulatingIdempotency] = useState<boolean>(false);
  const [reversingTxId, setReversingTxId] = useState<string | null>(null);
  const [errorMsg, setErrorMsg] = useState<string | null>(null);
  const [successMsg, setSuccessMsg] = useState<string | null>(null);

  // Modals
  const [isArchModalOpen, setIsArchModalOpen] = useState<boolean>(false);
  const [idempotencyResult, setIdempotencyResult] = useState<IdempotencyResultState | null>(null);

  // Copy helper
  const [copiedId, setCopiedId] = useState<string | null>(null);

  const client = useMemo(() => new LedgerApiClient(apiUrl), [apiUrl]);

  const copyToClipboard = (text: string, id: string) => {
    navigator.clipboard.writeText(text);
    setCopiedId(id);
    setTimeout(() => setCopiedId(null), 2000);
  };

  // Check health and initialize
  const checkSystemHealth = useCallback(async () => {
    setHealthStatus("CHECKING");
    const health = await client.checkHealth();
    setHealthStatus(health.status === "UP" ? "UP" : "DOWN");
    if (health.db) setDbType(health.db);
  }, [client]);

  useEffect(() => {
    setIdempotencyKey(generateUUID());
    setApiUrl(client.getBaseUrl());
    checkSystemHealth();
  }, [checkSystemHealth, client]);

  // Load account balances
  const refreshAccountBalances = useCallback(async (accountList: Account[]) => {
    const updated = await Promise.all(
      accountList.map(async (acc) => {
        try {
          const bal = await client.getBalance(acc.id);
          return { ...acc, balance: bal.balance };
        } catch {
          return acc;
        }
      })
    );
    setAccounts(updated);
    if (activeAccount) {
      const refreshedActive = updated.find((a) => a.id === activeAccount.id);
      if (refreshedActive) setActiveAccount(refreshedActive);
    }
  }, [client, activeAccount]);

  // Load history for active account
  const loadHistory = useCallback(async (accountId: string) => {
    try {
      const data = await client.getTransactions(accountId, 0, 30);
      setHistory(data.content);
    } catch {
      setHistory([]);
    }
  }, [client]);

  useEffect(() => {
    if (activeAccount) {
      loadHistory(activeAccount.id);
    }
  }, [activeAccount, loadHistory]);

  // Auto-clear messages
  useEffect(() => {
    if (successMsg) {
      const t = setTimeout(() => setSuccessMsg(null), 5000);
      return () => clearTimeout(t);
    }
  }, [successMsg]);

  // Actions: Create Account
  const handleCreateAccount = async () => {
    setIsLoading(true);
    setErrorMsg(null);
    try {
      const newAcc = await client.createAccount("INR");
      const initialBalanceAcc = { ...newAcc, balance: 0 };
      const updated = [initialBalanceAcc, ...accounts];
      setAccounts(updated);
      setActiveAccount(initialBalanceAcc);
      
      // Auto select in transfer form
      if (!fromId) setFromId(newAcc.id);
      else if (!toId && fromId !== newAcc.id) setToId(newAcc.id);

      setSuccessMsg(`Wallet created: ${newAcc.id.slice(0, 8)}...`);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "Failed to create account";
      setErrorMsg(msg);
    } finally {
      setIsLoading(false);
    }
  };

  // Actions: Quick Deposit
  const handleDeposit = async (accountId: string) => {
    const paise = Math.round(parseFloat(depositAmountRupees) * 100);
    if (isNaN(paise) || paise <= 0) {
      setErrorMsg("Please enter a valid deposit amount");
      return;
    }

    setIsLoading(true);
    setErrorMsg(null);
    try {
      await client.deposit(accountId, paise, generateUUID());
      await refreshAccountBalances(accounts);
      if (activeAccount?.id === accountId) {
        await loadHistory(accountId);
      }
      setDepositingAccountId(null);
      setSuccessMsg(`Deposited ${formatINR(paise)} successfully!`);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "Deposit failed";
      setErrorMsg(msg);
    } finally {
      setIsLoading(false);
    }
  };

  // Actions: Transfer
  const handleTransfer = async () => {
    if (!fromId || !toId) {
      setErrorMsg("Please select both sender and receiver wallets");
      return;
    }
    if (fromId === toId) {
      setErrorMsg("Sender and receiver accounts cannot be identical");
      return;
    }
    const paise = Math.round(parseFloat(amountRupees) * 100);
    if (isNaN(paise) || paise <= 0) {
      setErrorMsg("Please enter a valid amount");
      return;
    }

    setIsTransferring(true);
    setErrorMsg(null);
    try {
      await client.transfer(fromId, toId, paise, idempotencyKey);
      await refreshAccountBalances(accounts);
      if (activeAccount?.id === fromId || activeAccount?.id === toId) {
        await loadHistory(activeAccount.id);
      }
      // Regenerate key for next transaction
      setIdempotencyKey(generateUUID());
      setSuccessMsg(`Transfer of ${formatINR(paise)} completed successfully!`);
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "Transfer failed";
      setErrorMsg(msg);
    } finally {
      setIsTransferring(false);
    }
  };

  // Actions: Idempotency Race Test
  const handleSimulateIdempotency = async () => {
    if (!fromId || !toId) {
      setErrorMsg("Please select both sender and receiver wallets");
      return;
    }
    if (fromId === toId) {
      setErrorMsg("Sender and receiver accounts cannot be identical");
      return;
    }
    const paise = Math.round(parseFloat(amountRupees) * 100);
    if (isNaN(paise) || paise <= 0) {
      setErrorMsg("Please enter a valid amount");
      return;
    }

    setIsSimulatingIdempotency(true);
    setErrorMsg(null);
    const key = idempotencyKey || generateUUID();

    try {
      const senderBalBefore = accounts.find((a) => a.id === fromId)?.balance || 0;

      // Send Request 1
      const start1 = performance.now();
      const res1 = await client.transfer(fromId, toId, paise, key);
      const durationMs1 = Math.round(performance.now() - start1);

      // Immediately send Request 2 with IDENTICAL key
      const start2 = performance.now();
      const res2 = await client.transfer(fromId, toId, paise, key);
      const durationMs2 = Math.round(performance.now() - start2);

      // Refresh balances
      await refreshAccountBalances(accounts);
      const balAfterRes = await client.getBalance(fromId);
      const senderBalAfter = balAfterRes.balance;

      if (activeAccount?.id === fromId || activeAccount?.id === toId) {
        await loadHistory(activeAccount.id);
      }

      setIdempotencyResult({
        key,
        amountPaise: paise,
        res1,
        res2,
        durationMs1,
        durationMs2,
        initialBalance: senderBalBefore,
        finalBalance: senderBalAfter,
      });

      // Regenerate key
      setIdempotencyKey(generateUUID());
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "Idempotency test failed";
      setErrorMsg(msg);
    } finally {
      setIsSimulatingIdempotency(false);
    }
  };

  // Actions: Reversal
  const handleReverse = async (transactionId: string) => {
    setReversingTxId(transactionId);
    setErrorMsg(null);
    try {
      await client.reverse(transactionId);
      await refreshAccountBalances(accounts);
      if (activeAccount) {
        await loadHistory(activeAccount.id);
      }
      setSuccessMsg("Transaction reversed! Compensating ledger entries posted.");
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "Reversal failed";
      setErrorMsg(msg);
    } finally {
      setReversingTxId(null);
    }
  };

  // 1-Click Sandbox Seeder (Creates 2 accounts, seeds with 5k, sends 1k)
  const handleSeedSandbox = async () => {
    setIsLoading(true);
    setErrorMsg(null);
    try {
      const acc1 = await client.createAccount("INR");
      const acc2 = await client.createAccount("INR");

      // Seed acc1 with 500,000 paise (₹5,000)
      await client.deposit(acc1.id, 500000, generateUUID());

      // Transfer 100,000 paise (₹1,000) from acc1 to acc2
      await client.transfer(acc1.id, acc2.id, 100000, generateUUID());

      const updated = [
        { ...acc1, balance: 400000 },
        { ...acc2, balance: 100000 },
      ];
      setAccounts(updated);
      setActiveAccount(updated[0]);
      setFromId(acc1.id);
      setToId(acc2.id);
      await loadHistory(acc1.id);

      setSuccessMsg("Demo Sandbox Seeded! 2 wallets created with ₹5,000 and ₹1,000 transfer executed.");
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : "Seeding failed";
      setErrorMsg(msg);
    } finally {
      setIsLoading(false);
    }
  };

  return (
    <div className="min-h-screen bg-zinc-950 text-zinc-100 selection:bg-emerald-500 selection:text-zinc-950">
      {/* Top Navigation */}
      <Navbar
        healthStatus={healthStatus}
        dbType={dbType}
        apiUrl={apiUrl}
        onUpdateApiUrl={(newUrl) => {
          setApiUrl(newUrl);
          client.setBaseUrl(newUrl);
          checkSystemHealth();
        }}
        onOpenArchModal={() => setIsArchModalOpen(true)}
      />

      {/* Main Content */}
      <main className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8 space-y-8">
        {/* Banner Alert Messages */}
        {errorMsg && (
          <div className="p-4 rounded-xl bg-rose-500/10 border border-rose-500/20 text-rose-300 text-sm flex items-center justify-between">
            <span>{errorMsg}</span>
            <button onClick={() => setErrorMsg(null)} className="text-rose-400 hover:text-rose-200">✕</button>
          </div>
        )}
        {successMsg && (
          <div className="p-4 rounded-xl bg-emerald-500/10 border border-emerald-500/20 text-emerald-300 text-sm flex items-center justify-between">
            <span className="flex items-center gap-2">
              <CheckCircle2 className="w-4 h-4 text-emerald-400" />
              {successMsg}
            </span>
            <button onClick={() => setSuccessMsg(null)} className="text-emerald-400 hover:text-emerald-200">✕</button>
          </div>
        )}

        {/* Hero Metrics Strip */}
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="p-4 rounded-2xl bg-zinc-900 border border-zinc-800 space-y-1">
            <span className="text-xs font-mono uppercase text-zinc-400">Ledger Invariant</span>
            <div className="text-lg font-bold text-emerald-400 flex items-center gap-1.5">
              <ShieldCheck className="w-5 h-5" /> ∑(Entries) = 0
            </div>
            <p className="text-[11px] text-zinc-400">Deferred commit-time DB trigger</p>
          </div>

          <div className="p-4 rounded-2xl bg-zinc-900 border border-zinc-800 space-y-1">
            <span className="text-xs font-mono uppercase text-zinc-400">Concurrency Safety</span>
            <div className="text-lg font-bold text-cyan-400 flex items-center gap-1.5">
              <Zap className="w-5 h-5" /> SELECT FOR UPDATE
            </div>
            <p className="text-[11px] text-zinc-400">UUID-ordered deadlock prevention</p>
          </div>

          <div className="p-4 rounded-2xl bg-zinc-900 border border-zinc-800 space-y-1">
            <span className="text-xs font-mono uppercase text-zinc-400">Idempotency Layer</span>
            <div className="text-lg font-bold text-amber-400 flex items-center gap-1.5">
              <RefreshCw className="w-5 h-5" /> Two-Layer + Race
            </div>
            <p className="text-[11px] text-zinc-400">Zero double-spending under retries</p>
          </div>

          <div className="p-4 rounded-2xl bg-zinc-900 border border-zinc-800 space-y-1">
            <span className="text-xs font-mono uppercase text-zinc-400">Event Delivery</span>
            <div className="text-lg font-bold text-purple-400 flex items-center gap-1.5">
              <Layers className="w-5 h-5" /> Outbox ➔ Kafka
            </div>
            <p className="text-[11px] text-zinc-400">At-least-once with acks=all</p>
          </div>
        </div>

        {/* 3-Column Dashboard */}
        <div className="grid grid-cols-1 lg:grid-cols-12 gap-8">
          
          {/* Col 1: Wallet Manager (4 cols) */}
          <div className="lg:col-span-4 space-y-4">
            <div className="flex items-center justify-between">
              <h2 className="font-bold text-base text-zinc-100 flex items-center gap-2">
                <Wallet className="w-4 h-4 text-emerald-400" /> Wallet Accounts
              </h2>
              <div className="flex items-center space-x-2">
                {accounts.length === 0 && (
                  <button
                    onClick={handleSeedSandbox}
                    disabled={isLoading}
                    className="px-2.5 py-1 text-xs font-semibold rounded-lg bg-emerald-500/10 text-emerald-400 hover:bg-emerald-500/20 border border-emerald-500/30 transition flex items-center gap-1"
                    title="Creates 2 accounts with seed balances"
                  >
                    <Sparkles className="w-3 h-3" /> Quick Seed
                  </button>
                )}
                <button
                  onClick={handleCreateAccount}
                  disabled={isLoading}
                  className="px-2.5 py-1 text-xs font-semibold rounded-lg bg-emerald-500 text-zinc-950 hover:bg-emerald-400 transition flex items-center gap-1"
                >
                  <PlusCircle className="w-3.5 h-3.5" /> New
                </button>
              </div>
            </div>

            {/* Account List */}
            {accounts.length === 0 ? (
              <div className="p-8 rounded-2xl border border-dashed border-zinc-800 text-center space-y-3">
                <div className="w-10 h-10 rounded-full bg-zinc-900 flex items-center justify-center mx-auto text-zinc-500">
                  <Wallet className="w-5 h-5" />
                </div>
                <p className="text-xs text-zinc-400">No active wallets in this session</p>
                <div className="flex justify-center gap-2 pt-2">
                  <button
                    onClick={handleSeedSandbox}
                    className="px-3 py-1.5 rounded-lg bg-emerald-500/10 border border-emerald-500/30 text-emerald-400 text-xs font-medium hover:bg-emerald-500/20 transition flex items-center gap-1.5"
                  >
                    <Sparkles className="w-3.5 h-3.5" /> 1-Click Seed Demo
                  </button>
                </div>
              </div>
            ) : (
              <div className="space-y-3 max-h-[600px] overflow-y-auto pr-1">
                {accounts.map((acc, index) => {
                  const isActive = activeAccount?.id === acc.id;
                  const isDepositing = depositingAccountId === acc.id;

                  return (
                    <div
                      key={acc.id}
                      onClick={() => setActiveAccount(acc)}
                      className={`p-4 rounded-xl border transition cursor-pointer space-y-2.5 ${
                        isActive
                          ? "bg-zinc-900 border-emerald-500/50 ring-1 ring-emerald-500/20"
                          : "bg-zinc-900/60 border-zinc-800/80 hover:bg-zinc-900"
                      }`}
                    >
                      <div className="flex items-center justify-between text-xs">
                        <span className="font-semibold text-zinc-300 flex items-center gap-1.5">
                          <span className="w-2 h-2 rounded-full bg-emerald-400" />
                          Wallet #{index + 1}
                        </span>
                        <div className="flex items-center gap-1 text-[11px] font-mono text-zinc-500">
                          <span>{acc.id.slice(0, 8)}...</span>
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              copyToClipboard(acc.id, acc.id);
                            }}
                            className="p-1 hover:text-zinc-300"
                            title="Copy full UUID"
                          >
                            {copiedId === acc.id ? <Check className="w-3 h-3 text-emerald-400" /> : <Copy className="w-3 h-3" />}
                          </button>
                        </div>
                      </div>

                      {/* Balance Display */}
                      <div className="flex items-baseline justify-between">
                        <div>
                          <div className="text-xl font-bold tracking-tight text-zinc-100">
                            {formatINR(acc.balance ?? 0)}
                          </div>
                          <div className="text-[10px] font-mono text-zinc-500">
                            {(acc.balance ?? 0).toLocaleString()} paise
                          </div>
                        </div>

                        <button
                          onClick={(e) => {
                            e.stopPropagation();
                            setDepositingAccountId(isDepositing ? null : acc.id);
                          }}
                          className="px-2.5 py-1 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-xs font-mono transition"
                        >
                          + Deposit
                        </button>
                      </div>

                      {/* Deposit Drawer */}
                      {isDepositing && (
                        <div
                          onClick={(e) => e.stopPropagation()}
                          className="pt-2 border-t border-zinc-800 flex items-center space-x-2"
                        >
                          <div className="relative flex-1">
                            <span className="absolute left-2.5 top-1.5 text-xs text-zinc-500">₹</span>
                            <input
                              type="number"
                              min="1"
                              value={depositAmountRupees}
                              onChange={(e) => setDepositAmountRupees(e.target.value)}
                              placeholder="Amount"
                              className="w-full bg-zinc-950 border border-zinc-700 rounded-lg pl-6 pr-2 py-1 text-xs text-zinc-100 font-mono focus:outline-none focus:border-emerald-500"
                            />
                          </div>
                          <button
                            onClick={() => handleDeposit(acc.id)}
                            disabled={isLoading}
                            className="px-3 py-1 rounded-lg bg-emerald-500 hover:bg-emerald-400 text-zinc-950 font-semibold text-xs transition"
                          >
                            Add
                          </button>
                        </div>
                      )}
                    </div>
                  );
                })}
              </div>
            )}
          </div>

          {/* Col 2: Double-Entry Transfer Studio (4 cols) */}
          <div className="lg:col-span-4 space-y-4">
            <div className="flex items-center justify-between">
              <h2 className="font-bold text-base text-zinc-100 flex items-center gap-2">
                <ArrowRightLeft className="w-4 h-4 text-cyan-400" /> Transfer Studio
              </h2>
              <span className="text-[11px] font-mono text-zinc-400">ACID + Idempotent</span>
            </div>

            <div className="p-5 rounded-2xl bg-zinc-900 border border-zinc-800 space-y-4">
              {/* Sender Selector */}
              <div>
                <label className="block text-xs font-mono uppercase text-zinc-400 mb-1.5">
                  Source Wallet (Debit −)
                </label>
                <select
                  value={fromId}
                  onChange={(e) => setFromId(e.target.value)}
                  className="w-full bg-zinc-950 border border-zinc-800 rounded-xl px-3 py-2 text-xs font-mono text-zinc-200 focus:outline-none focus:border-cyan-500"
                >
                  <option value="">Select Sender Wallet</option>
                  {accounts.map((a, i) => (
                    <option key={a.id} value={a.id}>
                      Wallet #{i + 1} ({formatINR(a.balance ?? 0)})
                    </option>
                  ))}
                </select>
              </div>

              {/* Receiver Selector */}
              <div>
                <label className="block text-xs font-mono uppercase text-zinc-400 mb-1.5">
                  Destination Wallet (Credit +)
                </label>
                <select
                  value={toId}
                  onChange={(e) => setToId(e.target.value)}
                  className="w-full bg-zinc-950 border border-zinc-800 rounded-xl px-3 py-2 text-xs font-mono text-zinc-200 focus:outline-none focus:border-cyan-500"
                >
                  <option value="">Select Receiver Wallet</option>
                  {accounts.map((a, i) => (
                    <option key={a.id} value={a.id} disabled={a.id === fromId}>
                      Wallet #{i + 1} ({formatINR(a.balance ?? 0)})
                    </option>
                  ))}
                </select>
              </div>

              {/* Amount */}
              <div>
                <label className="block text-xs font-mono uppercase text-zinc-400 mb-1.5">
                  Amount in Rupees (₹)
                </label>
                <div className="relative">
                  <span className="absolute left-3 top-2 text-sm text-zinc-400 font-mono">₹</span>
                  <input
                    type="number"
                    min="1"
                    value={amountRupees}
                    onChange={(e) => setAmountRupees(e.target.value)}
                    className="w-full bg-zinc-950 border border-zinc-800 rounded-xl pl-8 pr-3 py-2 text-sm font-mono text-zinc-100 focus:outline-none focus:border-cyan-500"
                  />
                </div>
                <div className="text-[11px] font-mono text-zinc-400 mt-1 flex justify-between">
                  <span>Minor units:</span>
                  <span>{(parseFloat(amountRupees || "0") * 100 || 0).toLocaleString()} paise</span>
                </div>
              </div>

              {/* Idempotency Key */}
              <div>
                <div className="flex items-center justify-between mb-1.5">
                  <label className="text-xs font-mono uppercase text-zinc-400">
                    Idempotency-Key Header
                  </label>
                  <button
                    onClick={() => setIdempotencyKey(generateUUID())}
                    className="text-[11px] text-cyan-400 hover:text-cyan-300 font-mono flex items-center gap-1"
                  >
                    <RefreshCw className="w-3 h-3" /> New Key
                  </button>
                </div>
                <input
                  type="text"
                  value={idempotencyKey}
                  onChange={(e) => setIdempotencyKey(e.target.value)}
                  className="w-full bg-zinc-950 border border-zinc-800 rounded-xl px-3 py-2 text-xs font-mono text-zinc-400 focus:outline-none focus:border-cyan-500"
                />
              </div>

              {/* Actions */}
              <div className="pt-2 space-y-2.5">
                <button
                  onClick={handleTransfer}
                  disabled={isTransferring || isSimulatingIdempotency || !fromId || !toId}
                  className="w-full py-2.5 rounded-xl bg-cyan-500 hover:bg-cyan-400 disabled:bg-zinc-800 disabled:text-zinc-600 text-zinc-950 font-bold text-xs uppercase tracking-wider transition shadow-lg shadow-cyan-500/20"
                >
                  {isTransferring ? "Executing Transfer..." : "Execute Transfer"}
                </button>

                <button
                  onClick={handleSimulateIdempotency}
                  disabled={isTransferring || isSimulatingIdempotency || !fromId || !toId}
                  className="w-full py-2.5 rounded-xl bg-amber-500/10 hover:bg-amber-500/20 border border-amber-500/30 text-amber-400 font-bold text-xs transition flex items-center justify-center gap-2"
                  title="Sends 2 concurrent requests with the identical idempotency key to prove zero double-spend"
                >
                  <ShieldCheck className="w-4 h-4" />
                  {isSimulatingIdempotency ? "Simulating Race..." : "Simulate Network Retry (Test Idempotency)"}
                </button>
              </div>
            </div>
          </div>

          {/* Col 3: Live Double-Entry Audit Trail (4 cols) */}
          <div className="lg:col-span-4 space-y-4">
            <div className="flex items-center justify-between">
              <h2 className="font-bold text-base text-zinc-100 flex items-center gap-2">
                <Clock className="w-4 h-4 text-purple-400" /> Live Audit Trail
              </h2>
              {activeAccount && (
                <button
                  onClick={() => loadHistory(activeAccount.id)}
                  className="text-[11px] font-mono text-purple-400 hover:text-purple-300 flex items-center gap-1"
                >
                  <RefreshCw className="w-3 h-3" /> Refresh
                </button>
              )}
            </div>

            <div className="p-4 rounded-2xl bg-zinc-900 border border-zinc-800 min-h-[400px]">
              {!activeAccount ? (
                <div className="p-8 text-center text-xs text-zinc-400">
                  Select a wallet on the left to view its immutable transaction history.
                </div>
              ) : history.length === 0 ? (
                <div className="p-8 text-center text-xs text-zinc-400">
                  No posted transactions for this account yet.
                </div>
              ) : (
                <div className="space-y-3 max-h-[550px] overflow-y-auto pr-1">
                  {history.map((tx) => {
                    const isCredit = tx.direction === "CREDIT";
                    const isReversing = reversingTxId === tx.transactionId;

                    return (
                      <div
                        key={tx.transactionId}
                        className="p-3.5 rounded-xl bg-zinc-950 border border-zinc-800/80 space-y-2 text-xs"
                      >
                        <div className="flex items-center justify-between">
                          <span
                            className={`px-2 py-0.5 rounded font-mono text-[10px] uppercase font-bold tracking-wider ${
                              tx.type === "DEPOSIT"
                                ? "bg-emerald-500/10 text-emerald-400 border border-emerald-500/20"
                                : tx.type === "TRANSFER"
                                ? "bg-cyan-500/10 text-cyan-400 border border-cyan-500/20"
                                : "bg-purple-500/10 text-purple-400 border border-purple-500/20"
                            }`}
                          >
                            {tx.type}
                          </span>

                          <span className="font-mono text-zinc-400 text-[10px]">
                            {new Date(tx.createdAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })}
                          </span>
                        </div>

                        {/* Amount & Direction */}
                        <div className="flex items-baseline justify-between">
                          <div className="flex items-center gap-1.5 font-bold text-sm">
                            {isCredit ? (
                              <ArrowDownLeft className="w-4 h-4 text-emerald-400" />
                            ) : (
                              <ArrowUpRight className="w-4 h-4 text-rose-400" />
                            )}
                            <span className={isCredit ? "text-emerald-400" : "text-rose-400"}>
                              {isCredit ? "+" : "−"}
                              {formatINR(tx.amount)}
                            </span>
                          </div>

                          <span className="text-[10px] font-mono text-zinc-400">
                            {tx.amount.toLocaleString()} paise
                          </span>
                        </div>

                        {/* Counterparty & Actions */}
                        <div className="flex items-center justify-between pt-1 border-t border-zinc-850 text-[11px] font-mono text-zinc-400">
                          <div className="truncate max-w-[150px]" title={tx.counterpartyAccountId || "System Clearing"}>
                            {tx.counterpartyAccountId ? `CP: ${tx.counterpartyAccountId.slice(0, 8)}...` : "System Escrow"}
                          </div>

                          {tx.type === "TRANSFER" && (
                            <button
                              onClick={() => handleReverse(tx.transactionId)}
                              disabled={isReversing}
                              className="px-2 py-0.5 rounded bg-zinc-800 hover:bg-zinc-700 text-zinc-300 text-[10px] font-medium transition"
                            >
                              {isReversing ? "Reversing..." : "Reverse"}
                            </button>
                          )}
                        </div>
                      </div>
                    );
                  })}
                </div>
              )}
            </div>
          </div>
        </div>
      </main>

      {/* Modals */}
      <ArchitectureModal
        isOpen={isArchModalOpen}
        onClose={() => setIsArchModalOpen(false)}
      />

      <IdempotencyModal
        isOpen={!!idempotencyResult}
        onClose={() => setIdempotencyResult(null)}
        result={idempotencyResult}
      />
    </div>
  );
}
