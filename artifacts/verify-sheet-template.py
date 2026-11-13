"""Read-only exported workbook QA; render cell values/styles without editing the workbook."""
from pathlib import Path
import json
from openpyxl import load_workbook
from PIL import Image, ImageDraw, ImageFont

root = Path(__file__).resolve().parent.parent
source = root / 'outputs' / '01a1d355-637e-7883-8d9f-d99b2108ece5' / 'family-records-template.xlsx'
out = root / '.tooling' / 'sheet-preview'
out.mkdir(parents=True, exist_ok=True)
wb = load_workbook(source, data_only=False)
expected_names = ['定位回報', '家庭成員', '行事曆', '回報設定', '操作紀錄', '使用說明']
assert wb.sheetnames == expected_names, wb.sheetnames
font_path = Path('C:/Windows/Fonts/msjh.ttc')
bold_path = Path('C:/Windows/Fonts/msjhbd.ttc')
results = []

def color(value, fallback):
    if value and value.type == 'rgb' and isinstance(value.rgb, str):
        return '#' + value.rgb[-6:]
    return fallback

for ws in wb.worksheets:
    rows = 19 if ws.title == '使用說明' else 6
    cols = 2 if ws.title == '使用說明' else ws.max_column
    if ws.title != '使用說明':
        assert ws.freeze_panes == 'C2', (ws.title, ws.freeze_panes)
        for row in ws.iter_rows(min_row=2):
            assert all(c.value is None for c in row), 'Unexpected sample data'
    widths = []
    for index in range(1, cols + 1):
        from openpyxl.utils import get_column_letter
        letter = get_column_letter(index)
        width = ws.column_dimensions[letter].width
        # Export may use a ranged column dimension; find its owner.
        for dimension in ws.column_dimensions.values():
            if dimension.min is not None and dimension.min <= index <= (dimension.max or dimension.min):
                width = dimension.width
                break
        widths.append(round(width * 7 + 5))
    heights = [round((ws.row_dimensions[r].height or 18) * 96 / 72) for r in range(1, rows + 1)]
    image = Image.new('RGB', (sum(widths) + 24, sum(heights) + 54), '#F3F4F1')
    draw = ImageDraw.Draw(image)
    caption = ImageFont.truetype(str(font_path), 14)
    draw.text((12, 8), ws.title + '（已匯出檔案的儲存格樣式預覽）', font=caption, fill='#285C49')
    y = 38
    clipped = []
    for row in range(1, rows + 1):
        x = 12
        for col in range(1, cols + 1):
            cell = ws.cell(row, col)
            fill = color(cell.fill.fgColor, '#FFFFFF') if cell.fill.patternType == 'solid' else '#FFFFFF'
            width, height = widths[col-1], heights[row-1]
            draw.rectangle((x, y, x+width, y+height), fill=fill)
            if row == 1 and ws.title != '使用說明':
                draw.line((x+width, y, x+width, y+height), fill='#FFFFFF')
            if cell.value is not None:
                font = ImageFont.truetype(str(bold_path if cell.font.bold else font_path), round((cell.font.sz or 11)*96/72))
                text = str(cell.value)
                lines = []
                for paragraph in text.split('\n'):
                    line = ''
                    for character in paragraph:
                        if line and draw.textlength(line+character, font=font) > width - 14 and cell.alignment.wrap_text:
                            lines.append(line)
                            line = character
                        else:
                            line += character
                    lines.append(line)
                line_height = font.size + 4
                if len(lines)*line_height > height + 1:
                    clipped.append(cell.coordinate)
                top = y + max(2, (height - len(lines)*line_height)//2)
                for number, line in enumerate(lines):
                    tx = x + 7
                    if cell.alignment.horizontal == 'center': tx = x + (width-draw.textlength(line,font=font))/2
                    draw.text((tx, top+number*line_height), line, font=font, fill=color(cell.font.color,'#263D34'))
            x += width
        y += heights[row-1]
    image.save(out / (ws.title + '.png'))
    results.append({'sheet':ws.title,'headers':[ws.cell(1,c).value for c in range(1,cols+1)],'freeze':ws.freeze_panes,'clippedCells':clipped})
assert not any(r['clippedCells'] for r in results), results
(out / 'verification.json').write_text(json.dumps({'workbook':str(source),'sheets':results,
    'renderMethod':'Read-only exported XLSX cell-style rendering; native Artifact Tool Vulkan rendering unavailable on this host.'},ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'status':'passed','sheets':len(results),'sampleRows':0,'clippedCells':0},ensure_ascii=False))
