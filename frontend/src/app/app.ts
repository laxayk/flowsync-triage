import { Component, OnDestroy, computed, effect, signal } from '@angular/core';
import { DatePipe, NgTemplateOutlet } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Api } from './api.service';
import { CATEGORIES, EmailRow, PRIORITIES, STATUSES, Stats, Ticket, TicketDetail } from './models';

type Tab = 'tickets' | 'review' | 'stats';

@Component({
  imports: [FormsModule, DatePipe, NgTemplateOutlet],
  selector: 'app-root',
  styleUrl: './app.css',
  templateUrl: './app.html',
})
export class App implements OnDestroy {
  readonly categories = CATEGORIES;
  readonly priorities = PRIORITIES;
  readonly statuses = STATUSES;

  username = 'agent';
  password = '';
  loginError = signal('');
  tab = signal<Tab>('tickets');
  live = signal(false);
  toast = signal('');

  tickets = signal<Ticket[]>([]);
  filterStatus = signal('');
  filterCategory = signal('');
  search = signal('');
  selected = signal<TicketDetail | null>(null);
  draftText = signal<Record<number, string>>({});

  held = signal<EmailRow[]>([]);
  quarantined = signal<EmailRow[]>([]);
  noTicket = signal<EmailRow[]>([]);
  stats = signal<Stats | null>(null);

  isApprover = computed(() => this.api.session()?.role === 'APPROVER');
  reviewCount = computed(() => this.held().length + this.quarantined().length);
  private es: EventSource | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;

  constructor(public api: Api) {
    effect(() => {
      if (this.api.session()) this.start();
    });
  }

  ngOnDestroy() { this.es?.close(); }

  async doLogin() {
    this.loginError.set('');
    try {
      await this.api.login(this.username, this.password);
      this.password = '';
    } catch {
      this.loginError.set('Wrong username or password');
    }
  }

  logout() {
    this.es?.close();
    this.live.set(false);
    this.selected.set(null);
    this.api.logout();
  }

  private start() {
    this.refreshAll();
    this.es?.close();
    this.es = this.api.stream(() => this.scheduleRefresh());
    if (this.es) {
      this.es.onopen = () => this.live.set(true);
      this.es.onerror = () => this.live.set(false);
    }
  }

  /** Coalesce bursts of events (a batch of mails) into one refresh. */
  private scheduleRefresh() {
    if (this.timer) return;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.refreshAll();
    }, 400);
  }

  async refreshAll() {
    try {
      const [t, h, q, n, s] = await Promise.all([
        this.api.tickets({ status: this.filterStatus(), category: this.filterCategory(), q: this.search() }),
        this.api.emails('SECURITY_HOLD'), this.api.emails('QUARANTINED'), this.api.emails('NO_TICKET'), this.api.stats(),
      ]);
      this.tickets.set(t); this.held.set(h); this.quarantined.set(q); this.noTicket.set(n); this.stats.set(s);
      const sel = this.selected();
      if (sel) this.selected.set(await this.api.ticket(sel.ticket.id));
    } catch (e: any) {
      if (e?.status === 401) this.logout();
    }
  }

  setFilter(kind: 'status' | 'category', v: string) {
    (kind === 'status' ? this.filterStatus : this.filterCategory).set(v);
    this.refreshAll();
  }

  async open(t: Ticket) { this.selected.set(await this.api.ticket(t.id)); }
  close() { this.selected.set(null); }

  async update(field: 'category' | 'priority' | 'status' | 'assignee', value: string) {
    const s = this.selected();
    if (!s) return;
    await this.api.patch(s.ticket.id, { [field]: value });
    this.flash('Saved');
    await this.refreshAll();
  }

  draftBody(id: number, original: string) { return this.draftText()[id] ?? original; }
  editDraft(id: number, v: string) { this.draftText.update((m) => ({ ...m, [id]: v })); }

  async approve(id: number, original: string) {
    try {
      const text = this.draftBody(id, original);
      if (text !== original) await this.api.editDraft(id, text);
      const r = await this.api.approve(id);
      this.flash(r.mode === 'live' ? `Sent to ${r.recipient}` : `Captured (not sent): demo mode, reply to ${r.recipient}`);
    } catch (e: any) { this.flash(e?.error?.message ?? 'Could not send'); }
    await this.refreshAll();
  }

  async reject(id: number) { await this.api.reject(id); await this.refreshAll(); }

  async release(e: EmailRow) {
    try { await this.api.release(e.id); this.flash('Released: ticket created for review'); }
    catch (x: any) { this.flash(x?.status === 403 ? 'Only an approver can release mail' : 'Could not release'); }
    await this.refreshAll();
  }

  async dismiss(e: EmailRow) {
    try { await this.api.dismiss(e.id); this.flash('Dismissed'); }
    catch (x: any) { this.flash(x?.status === 403 ? 'Only an approver can dismiss mail' : 'Could not dismiss'); }
    await this.refreshAll();
  }

  async loadSamples() {
    const r = await this.api.loadSamples();
    this.flash(r.ingested ? `Loaded ${r.ingested} sample emails` : 'Sample mailbox already loaded');
  }

  flash(msg: string) {
    this.toast.set(msg);
    setTimeout(() => this.toast.set(''), 3500);
  }

  entries(o: Record<string, number> | undefined) { return o ? Object.entries(o) : []; }
  verdictLabel(v: string | null) {
    return ({ PROMPT_INJECTION: 'Prompt injection', PHISHING: 'Phishing', SUSPICIOUS_ATTACHMENT: 'Risky attachment',
      SPAM: 'Spam', SALES: 'Sales pitch', AUTO_REPLY: 'Auto-reply', CLEAN: 'Clean' } as Record<string, string>)[v ?? ''] ?? v ?? '';
  }
}
