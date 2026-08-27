// Every example has a `<pre id="log">` panel below the video/tiles — this
// writes timestamped lines to it and keeps it scrolled to the latest one.
const logEl = document.querySelector<HTMLPreElement>('#log')!;

export function log(line: string): void {
  const time = new Date().toLocaleTimeString();

  logEl.textContent += `[${time}] ${line}\n`;
  logEl.scrollTop = logEl.scrollHeight;
}
