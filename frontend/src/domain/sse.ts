// Incremental Server-Sent Events parser (WHATWG "event stream interpretation"), pure.
//
// Feed decoded text chunks as they arrive; complete events are returned as soon as their
// terminating blank line is seen. Chunks may split anywhere (inside a line, between CR and LF).

export interface SseEvent {
  /** `event:` field, default "message". */
  event: string;
  /** `data:` lines joined with "\n". */
  data: string;
  /** Last `id:` seen (sticky across events, per spec), or null. */
  id: string | null;
  /** `retry:` reconnection time in ms if this event carried one. */
  retry: number | null;
}

export interface SseParser {
  /** Parse a chunk; returns the events completed by it (possibly none). */
  push(chunk: string): SseEvent[];
  /** Number of comment lines (e.g. `: ping`) seen so far — handy as a liveness signal. */
  readonly comments: number;
}

export function createSseParser(): SseParser {
  let buffer = '';
  let pendingCr = false;
  let eventType = '';
  let data: string[] = [];
  let hasData = false;
  let lastId: string | null = null;
  let retry: number | null = null;
  let comments = 0;

  const dispatch = (out: SseEvent[]) => {
    if (hasData) {
      out.push({ event: eventType || 'message', data: data.join('\n'), id: lastId, retry });
    }
    eventType = '';
    data = [];
    hasData = false;
    retry = null;
  };

  const processLine = (line: string, out: SseEvent[]) => {
    if (line === '') {
      dispatch(out);
      return;
    }
    if (line.startsWith(':')) {
      comments++;
      return;
    }
    const colon = line.indexOf(':');
    const field = colon === -1 ? line : line.slice(0, colon);
    let value = colon === -1 ? '' : line.slice(colon + 1);
    if (value.startsWith(' ')) value = value.slice(1);
    switch (field) {
      case 'event':
        eventType = value;
        break;
      case 'data':
        data.push(value);
        hasData = true;
        break;
      case 'id':
        if (!value.includes('\0')) lastId = value;
        break;
      case 'retry':
        if (/^\d+$/.test(value)) retry = Number(value);
        break;
      default:
        // Unknown fields are ignored per spec.
        break;
    }
  };

  return {
    push(chunk: string): SseEvent[] {
      const out: SseEvent[] = [];
      let text = chunk;
      if (pendingCr && text.startsWith('\n')) text = text.slice(1); // CRLF split across chunks
      pendingCr = false;
      buffer += text;
      let start = 0;
      for (let i = 0; i < buffer.length; i++) {
        const c = buffer[i];
        if (c === '\n' || c === '\r') {
          processLine(buffer.slice(start, i), out);
          if (c === '\r') {
            if (i + 1 < buffer.length) {
              if (buffer[i + 1] === '\n') i++;
            } else {
              pendingCr = true;
            }
          }
          start = i + 1;
        }
      }
      buffer = buffer.slice(start);
      return out;
    },
    get comments() {
      return comments;
    },
  };
}
