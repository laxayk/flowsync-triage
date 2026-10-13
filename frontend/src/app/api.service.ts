import { Injectable, signal } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { EmailRow, Session, Stats, Ticket, TicketDetail } from './models';

@Injectable({ providedIn: 'root' })
export class Api {
  readonly session = signal<Session | null>(JSON.parse(sessionStorage.getItem('session') ?? 'null'));

  constructor(private http: HttpClient) {}

  private opts() {
    const s = this.session();
    return { headers: new HttpHeaders(s ? { Authorization: `Bearer ${s.token}` } : {}) };
  }

  async login(username: string, password: string): Promise<void> {
    const s = await firstValueFrom(this.http.post<Session>('/api/auth/login', { username, password }));
    sessionStorage.setItem('session', JSON.stringify(s));
    this.session.set(s);
  }

  logout() {
    sessionStorage.removeItem('session');
    this.session.set(null);
  }

  tickets(q: { status?: string; category?: string; q?: string }) {
    const params: Record<string, string> = {};
    for (const [k, v] of Object.entries(q)) if (v) params[k] = v;
    return firstValueFrom(this.http.get<Ticket[]>('/api/tickets', { ...this.opts(), params }));
  }
  ticket(id: number) { return firstValueFrom(this.http.get<TicketDetail>(`/api/tickets/${id}`, this.opts())); }
  patch(id: number, body: Partial<Ticket>) { return firstValueFrom(this.http.patch<Ticket>(`/api/tickets/${id}`, body, this.opts())); }
  emails(disposition: string) { return firstValueFrom(this.http.get<EmailRow[]>('/api/emails', { ...this.opts(), params: { disposition } })); }
  release(id: number) { return firstValueFrom(this.http.post(`/api/emails/${id}/release`, {}, this.opts())); }
  dismiss(id: number) { return firstValueFrom(this.http.post(`/api/emails/${id}/dismiss`, {}, this.opts())); }
  editDraft(id: number, body: string) { return firstValueFrom(this.http.put(`/api/drafts/${id}`, { body }, this.opts())); }
  approve(id: number) { return firstValueFrom(this.http.post<{ mode: string; recipient: string }>(`/api/drafts/${id}/approve`, {}, this.opts())); }
  regenerate(ticketId: number) { return firstValueFrom(this.http.post(`/api/tickets/${ticketId}/regenerate`, {}, this.opts())); }
  reject(id: number) { return firstValueFrom(this.http.post(`/api/drafts/${id}/reject`, {}, this.opts())); }
  loadSamples() { return firstValueFrom(this.http.post<{ ingested: number }>('/api/ingest/samples', {}, this.opts())); }
  stats() { return firstValueFrom(this.http.get<Stats>('/api/stats', this.opts())); }

  stream(onEvent: () => void): EventSource | null {
    const s = this.session();
    if (!s) return null;
    const es = new EventSource(`/api/stream?token=${encodeURIComponent(s.token)}`);
    for (const t of ['ticket', 'email']) es.addEventListener(t, onEvent);
    return es;
  }
}
