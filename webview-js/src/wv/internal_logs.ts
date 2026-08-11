const printInternalLog = false;
const saveLogRecords = window.saveYtLogRecords;
const maxLogRecords = window.maxYtLogRecords || 500;
export const internalLogRecords: string[] = [];

declare global {
  interface Window {
    handleYtInternalLog?: (level: string, tag: string, arg: (() => string) | any) => void;
  }
}

window.handleYtInternalLog = (level: string, tag: string, arg: (() => string) | any) => {
  if (!printInternalLog && !saveLogRecords) {
    return;
  }
  const log = `${new Date().toISOString()}-${level}-${tag}: ${typeof arg == 'function' ? arg() : arg}`;
  if (DEBUG && printInternalLog) {
    console.log(log);
  }
  if (saveLogRecords) {
    internalLogRecords.push(log);
    if (internalLogRecords.length > maxLogRecords + 50) {
      internalLogRecords.splice(0, internalLogRecords.length - maxLogRecords);
    }
  }
};
