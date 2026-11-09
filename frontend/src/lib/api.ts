export interface Account {
  id: string;
  currency: string;
  createdAt: string;
  balance?: number; // In paise
}

export interface BalanceResponse {
  accountId: string;
  currency: string;
  balance: number; // In paise
}

export interface DepositResponse {
  transactionId: string;
  accountId: string;
  amount: number; // In paise
  currency: string;
  status: string;
  balance: number; // In paise
}

export interface TransferResponse {
  transactionId: string;
  fromAccountId: string;
  toAccountId: string;
  amount: number; // In paise
  currency: string;
  status: string;
}

export interface ReversalResponse {
  reversalTransactionId: string;
  originalTransactionId: string;
  status: string;
  amount: number; // In paise
  currency: string;
}

export interface TransactionHistoryItem {
  transactionId: string;
  type: "DEPOSIT" | "TRANSFER" | "REVERSAL";
  status: "POSTED" | "PENDING" | "FAILED";
  amount: number; // In paise
  direction: "CREDIT" | "DEBIT";
  counterpartyAccountId: string | null;
  currency: string;
  createdAt: string;
}

export interface PaginatedHistory {
  content: TransactionHistoryItem[];
  totalPages: number;
  totalElements: number;
  size: number;
  number: number;
}

export function formatINR(paise: number): string {
  const rupees = paise / 100;
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  }).format(rupees);
}

export function generateUUID(): string {
  if (typeof crypto !== "undefined" && crypto.randomUUID) {
    return crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

// Client helper that uses either custom configured API URL, environment variable, or fallback
export class LedgerApiClient {
  private baseUrl: string;

  constructor(customUrl?: string) {
    this.baseUrl = customUrl || (typeof window !== "undefined" ? localStorage.getItem("ledger_api_url") || "" : "");
    if (!this.baseUrl) {
      this.baseUrl = process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080";
    }
    // Remove trailing slash if present
    this.baseUrl = this.baseUrl.replace(/\/+$/, "");
  }

  getBaseUrl(): string {
    return this.baseUrl;
  }

  setBaseUrl(url: string) {
    this.baseUrl = url.replace(/\/+$/, "");
    if (typeof window !== "undefined") {
      localStorage.setItem("ledger_api_url", this.baseUrl);
    }
  }

  private async fetchJson<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
    const url = `${this.baseUrl}${endpoint}`;
    const headers = {
      "Content-Type": "application/json",
      ...(options.headers || {}),
    };

    const res = await fetch(url, { ...options, headers });
    if (!res.ok) {
      let errorMsg = `HTTP Error ${res.status}`;
      try {
        const errorData = await res.json();
        errorMsg = errorData.message || errorData.error || errorMsg;
      } catch {
        // Fallback to text
        try {
          const text = await res.text();
          if (text) errorMsg = text;
        } catch {
          // ignore
        }
      }
      throw new Error(errorMsg);
    }

    return res.json() as Promise<T>;
  }

  async checkHealth(): Promise<{ status: string; db?: string }> {
    try {
      const data = await this.fetchJson<{ status: string; components?: { db?: { status: string; details?: { database?: string } } } }>("/actuator/health");
      return {
        status: data.status,
        db: data.components?.db?.details?.database || data.components?.db?.status || "Unknown",
      };
    } catch {
      return { status: "DOWN" };
    }
  }

  async createAccount(currency = "INR"): Promise<Account> {
    return this.fetchJson<Account>("/api/accounts", {
      method: "POST",
      body: JSON.stringify({ currency }),
    });
  }

  async getBalance(accountId: string): Promise<BalanceResponse> {
    return this.fetchJson<BalanceResponse>(`/api/accounts/${accountId}/balance`);
  }

  async deposit(accountId: string, amountPaise: number, idempotencyKey?: string): Promise<DepositResponse> {
    const headers: Record<string, string> = {};
    if (idempotencyKey) {
      headers["Idempotency-Key"] = idempotencyKey;
    }
    return this.fetchJson<DepositResponse>(`/api/accounts/${accountId}/deposits`, {
      method: "POST",
      headers,
      body: JSON.stringify({ amount: amountPaise }),
    });
  }

  async transfer(fromAccountId: string, toAccountId: string, amountPaise: number, idempotencyKey: string): Promise<TransferResponse> {
    return this.fetchJson<TransferResponse>("/api/transfers", {
      method: "POST",
      headers: {
        "Idempotency-Key": idempotencyKey,
      },
      body: JSON.stringify({
        fromAccountId,
        toAccountId,
        amount: amountPaise,
      }),
    });
  }

  async reverse(transactionId: string): Promise<ReversalResponse> {
    return this.fetchJson<ReversalResponse>(`/api/transfers/${transactionId}/reverse`, {
      method: "POST",
    });
  }

  async getTransactions(accountId: string, page = 0, size = 20): Promise<PaginatedHistory> {
    return this.fetchJson<PaginatedHistory>(`/api/accounts/${accountId}/transactions?page=${page}&size=${size}`);
  }
}

