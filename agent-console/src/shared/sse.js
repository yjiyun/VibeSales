export async function consumeSse(response, onEvent) {
  if (!response.body) throw new Error('SSE 响应没有可读流');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  const emit = frame => {
    if (!frame.trim()) return;
    let event = 'message';
    const data = [];
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim();
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart());
    }
    onEvent(event, JSON.parse(data.join('\n') || '{}'));
  };
  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done }).replace(/\r\n/g, '\n').replace(/\r/g, '\n');
    let end;
    while ((end = buffer.indexOf('\n\n')) >= 0) {
      emit(buffer.slice(0, end));
      buffer = buffer.slice(end + 2);
    }
    if (done) {
      emit(buffer);
      return;
    }
  }
}
