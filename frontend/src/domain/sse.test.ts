import { describe, expect, it } from 'vitest';
import { createSseParser } from './sse';

describe('createSseParser', () => {
  it('parses a ticket event', () => {
    const p = createSseParser();
    const events = p.push('event: ticket\ndata: {"id":"t1"}\n\n');
    expect(events).toEqual([{ event: 'ticket', data: '{"id":"t1"}', id: null, retry: null }]);
  });

  it('handles chunks split anywhere, including inside a line', () => {
    const p = createSseParser();
    const frame = 'event: ticket\ndata: {"id":"t1"}\n\nevent: ticket\ndata: {"id":"t2"}\n\n';
    const all = [];
    for (const ch of frame) all.push(...p.push(ch));
    expect(all.map((e) => e.data)).toEqual(['{"id":"t1"}', '{"id":"t2"}']);
  });

  it('supports CRLF and CR line endings, also when CRLF is split across chunks', () => {
    const p = createSseParser();
    expect(p.push('event: ticket\r\ndata: a\r')).toEqual([]);
    expect(p.push('\n\r\n')).toEqual([{ event: 'ticket', data: 'a', id: null, retry: null }]);
    expect(p.push('data: b\r\r')).toEqual([{ event: 'message', data: 'b', id: null, retry: null }]);
  });

  it('joins multi-line data and defaults the event type to message', () => {
    const p = createSseParser();
    expect(p.push('data: line1\ndata: line2\n\n')[0]).toMatchObject({
      event: 'message',
      data: 'line1\nline2',
    });
  });

  it('ignores comments (pings) and counts them', () => {
    const p = createSseParser();
    expect(p.push(': ping\n\n: ping\n\n')).toEqual([]);
    expect(p.comments).toBe(2);
  });

  it('does not dispatch events without data, and resets the event type afterwards', () => {
    const p = createSseParser();
    expect(p.push('event: ticket\n\n')).toEqual([]);
    expect(p.push('data: x\n\n')[0]?.event).toBe('message');
  });

  it('keeps the last id and parses retry', () => {
    const p = createSseParser();
    const [a] = p.push('id: 7\nretry: 3000\ndata: x\n\n');
    expect(a).toMatchObject({ id: '7', retry: 3000 });
    const [b] = p.push('data: y\n\n');
    expect(b).toMatchObject({ id: '7', retry: null });
    const [c] = p.push('retry: soon\ndata: z\n\n');
    expect(c?.retry).toBeNull();
  });

  it('handles fields without a colon or without a space after it', () => {
    const p = createSseParser();
    expect(p.push('data\n\n')[0]?.data).toBe('');
    expect(p.push('data:nospace\nfoo: bar\n\n')[0]?.data).toBe('nospace');
  });

  it('does not emit an unterminated trailing event', () => {
    const p = createSseParser();
    expect(p.push('event: ticket\ndata: {}')).toEqual([]);
    expect(p.push('\n\n')).toHaveLength(1);
  });
});
