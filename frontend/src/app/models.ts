export interface Ticket {
  id: number; key: string; subject: string; requester: string; category: string; priority: string;
  status: string; assignee: string | null; summary: string | null; confidence: number | null;
  classifiedBy: string | null; needsReview: boolean; createdAt: string; updatedAt: string;
}
export interface EmailRow {
  id: number; messageId: string; fromAddr: string; fromName: string; subject: string; body: string;
  attachments: string | null; receivedAt: string; source: string; status: string; verdict: string | null;
  verdictReason: string | null; category: string | null; disposition: string | null; ticketId: number | null;
  correlationId: string;
}
export interface Draft { id: number; ticketId: number; body: string; evidence: string | null; generatedBy: string; status: string; approvedBy: string | null; }
export interface Notification { id: number; channel: string; target: string; text: string; delivered: boolean; createdAt: string; }
export interface PipelineEvent { id: number; correlationId: string; stage: string; detail: string; latencyMs: number; }
export interface Audit { id: number; actor: string; action: string; detail: string; at: string; }
export interface TicketDetail {
  ticket: Ticket; emails: EmailRow[]; drafts: Draft[]; notifications: Notification[]; events: PipelineEvent[]; audit: Audit[];
}
export interface Session { token: string; username: string; role: string; }
export interface Stats {
  ticketsByCategory: Record<string, number>; ticketsByPriority: Record<string, number>;
  emailsByDisposition: Record<string, number>; emailsByVerdict: Record<string, number>;
  avgLatencyMsByStage: Record<string, number>;
  latencyPercentilesMs: Record<string, { p50: number; p95: number; n: number }>;
  llm: { enabled: boolean; provider: string; model: string; circuitOpen: boolean };
  sendMode: string;
}
export const CATEGORIES = ['QUERY', 'BUG', 'WRONG_BEHAVIOUR', 'ENHANCEMENT', 'BILLING', 'ACCOUNT_ACCESS', 'OUTAGE'];
export const PRIORITIES = ['P1', 'P2', 'P3', 'P4'];
export const STATUSES = ['OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED'];
