import fs from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { Workbook, SpreadsheetFile } from '@oai/artifact-tool';

const projectRoot = fileURLToPath(new URL('../', import.meta.url));
const outputDir = path.join(projectRoot, 'outputs', '01a1d355-637e-7883-8d9f-d99b2108ece5');
const previewDir = path.join(projectRoot, '.tooling', 'sheet-preview');
await fs.mkdir(outputDir, { recursive: true });
await fs.mkdir(previewDir, { recursive: true });

const tabs = [
  { name: '定位回報', headers: ['紀錄ID','家庭ID','操作','回報者ID','名稱','定位時間','緯度','經度','精度（公尺）','電量（%）','紀錄時間','原始資料（JSON）'], dates: [5,10], numbers: [6,7,8,9] },
  { name: '家庭成員', headers: ['紀錄ID','家庭ID','操作','成員ID','名稱','分享狀態','紀錄時間','原始資料（JSON）'], dates: [6], numbers: [] },
  { name: '行事曆', headers: ['紀錄ID','家庭ID','操作','活動ID','標題','開始時間','結束時間','時區','提前提醒（分鐘）','地點','備註','登錄者ID','紀錄時間','原始資料（JSON）'], dates: [5,6,12], numbers: [8] },
  { name: '回報設定', headers: ['紀錄ID','家庭ID','操作','成員ID','回報間隔（分鐘）','提醒開關','提醒位置','行事曆提醒開關','紀錄時間','原始資料（JSON）'], dates: [8], numbers: [4] },
  { name: '操作紀錄', headers: ['紀錄ID','家庭ID','資料類型','操作','使用者ID','實體ID','紀錄時間','原始資料（JSON）'], dates: [6], numbers: [] },
];
const wb = Workbook.create();
const letters = i => {
  let value = i + 1, out = '';
  while (value) { value--; out = String.fromCharCode(65 + value % 26) + out; value = Math.floor(value / 26); }
  return out;
};
for (const tab of tabs) {
  const sheet = wb.worksheets.add(tab.name);
  sheet.showGridLines = false;
  const last = letters(tab.headers.length - 1);
  const body = sheet.getRange(`A1:${last}21`);
  body.format.font = { name: 'Arial', size: 11, color: '#263D34' };
  body.format.rowHeight = 25;
  body.format.verticalAlignment = 'center';
  body.format.columnWidth = 19;
  body.format.fill = '#FFFFFF';
  sheet.getRange(`A1:${last}1`).values = [tab.headers];
  sheet.getRange(`A1:${last}1`).format = {
    fill: '#285C49', font: { name: 'Arial', size: 11, bold: true, color: '#FFFFFF' },
    horizontalAlignment: 'center', verticalAlignment: 'center', wrapText: true,
    rowHeight: 43,
    borders: { insideVertical: { style: 'thin', color: '#FFFFFF' } },
  };
  for (let col = 0; col < tab.headers.length; col++) {
    const label = tab.headers[col];
    const range = sheet.getRange(`${letters(col)}2:${letters(col)}201`);
    const column = sheet.getRange(`${letters(col)}1:${letters(col)}21`);
    if (label.endsWith('ID')) column.format.columnWidth = 27;
    if (label.includes('JSON')) column.format.columnWidth = 38;
    if (label === '備註') column.format.columnWidth = 32;
    range.setNumberFormat('@');
    if (tab.dates.includes(col)) {
      column.format.columnWidth = 25;
      range.setNumberFormat('yyyy-mm-dd hh:mm:ss');
    }
    if (tab.numbers.includes(col)) {
      range.setNumberFormat(label === '緯度' || label === '經度' ? '0.000000' : '0');
      range.format.horizontalAlignment = 'right';
    }
  }
  sheet.freezePanes.freezeRows(1);
  sheet.freezePanes.freezeColumns(2);
  console.log((await wb.inspect({kind:'table',range:`'${tab.name}'!A1:${last}2`,include:'values,formulas',tableMaxRows:2,tableMaxCols:14,maxChars:1800})).ndjson);
}

const guide = wb.worksheets.add('使用說明');
guide.showGridLines = false;
guide.tabColor = '#9EAFA7';
guide.getRange('A1:C22').format.font = { name:'Arial',size:11,color:'#263D34' };
guide.getRange('A1:A22').format.columnWidth = 25;
guide.getRange('B1:B22').format.columnWidth = 77;
guide.getRange('A1:C22').format.rowHeight = 30;
guide.getRange('B2').values = [['家庭共用紀錄']];
guide.getRange('B2').format.font = {name:'Arial',size:16,bold:true,color:'#285C49'};
guide.getRange('A4:B4').values = [['項目','使用方式']];
guide.getRange('A4:B4').format = {fill:'#285C49',font:{name:'Arial',size:11,bold:true,color:'#FFFFFF'},rowHeight:30};
const notes = [
  ['目前狀態','範本尚未連接家庭 App，尚無真實紀錄。'],
  ['定位回報','保存每次成功回報、更新與撤回的位置紀錄。'],
  ['家庭成員','保存加入、名稱、分享狀態與離開家庭的變更。'],
  ['行事曆','保存共用活動的新增、修改與刪除紀錄。'],
  ['回報設定','保存回報間隔、提示位置及提醒開關的變更。'],
  ['操作紀錄','保存家庭建立、名稱及其他家庭層級變更。'],
  ['時間顯示','日期時間以台北時間（Asia/Taipei，UTC+8）顯示。'],
  ['原始資料','JSON 欄位保留完整業務欄位及原始 ISO 時間。'],
  ['歷史保留','停止分享會撤回地圖位置，已匯入的歷史紀錄仍保留。'],
  ['試算表權限','由擁有者設定可查看此試算表的家庭成員。'],
  ['修改紀錄','請在 App 修改行程；此表的每一列代表一次變更。'],
  ['同步條件','完成 App 後端、Google 寫入授權及定時同步後才會有資料。'],
  ['欄位名稱','同步程式依第 1 列表頭寫入，請保留五個紀錄分頁的表頭順序。'],
  ['資料起點','紀錄從第 2 列開始追加。範本未放入示範位置或家人資料。'],
];
guide.getRange(`A5:B${4+notes.length}`).values = notes;
guide.getRange(`B5:B${4+notes.length}`).format.wrapText = true;
guide.getRange(`A5:A${4+notes.length}`).format.font.bold = true;
guide.getRange('A13:B13').format.fill = '#FFF0CC';
wb.recalculate();
const scan = await wb.inspect({kind:'match',searchTerm:'#REF!|#DIV/0!|#VALUE!|#NAME\\?|#N/A|#NUM!|#NULL!|#SPILL!|#CALC!',options:{useRegex:true,maxResults:10},maxChars:1200});
console.log(scan.ndjson);
const target = path.join(outputDir,'family-records-template.xlsx');
await (await SpreadsheetFile.exportXlsx(wb)).save(target);
console.log(`Created: ${target}`);
if (process.argv.includes('--render-help')) console.log(wb.help('workbook.render',{include:'index,notes,examples',maxChars:3500}).ndjson);
if (process.argv.includes('--render')) for (const tab of [...tabs, {name:'使用說明'}]) {
  const blob = await wb.render({sheetName:tab.name,range:tab.name==='使用說明'?'A1:B19':`A1:${letters(tab.headers.length-1)}6`,scale:1,format:'png'});
  await fs.writeFile(path.join(previewDir, `${tab.name}.png`),new Uint8Array(await blob.arrayBuffer()));
}
