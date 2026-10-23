"""Generate sharing PDFs from Markdown (requires reportlab and Mermaid CLI/mmdc)."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import (
    SimpleDocTemplate, Paragraph, Spacer, Table, TableStyle, PageBreak, Image,
)

ROOT = Path(__file__).resolve().parents[2]
WIDTH = A4[0] - 88
INK = colors.HexColor('#25354a')
BLUE = colors.HexColor('#165779')


def clean(value):
    value = re.sub(r'\[([^\]]+)\]\([^)]+\)', r'\1', value)
    return value.replace('**', '').replace('`', '')


def build(sources, output, label):
    body = ParagraphStyle('body', fontName='Korean', fontSize=9, leading=13,
                          wordWrap='CJK', textColor=INK, spaceAfter=6)
    cell = ParagraphStyle('cell', parent=body, fontSize=8, leading=12, spaceAfter=0)
    code = ParagraphStyle('code', parent=cell, fontSize=8, leading=12)
    title = ParagraphStyle('title', parent=body, fontSize=19, leading=26,
                           textColor=BLUE, spaceAfter=14, keepWithNext=True)
    h2 = ParagraphStyle('h2', parent=body, fontSize=12, leading=18,
                        textColor=BLUE, spaceBefore=12, spaceAfter=7, keepWithNext=True)
    h3 = ParagraphStyle('h3', parent=h2, fontSize=10, leading=15, spaceBefore=7)
    story = []

    def para(value, style=body):
        return Paragraph(escape(clean(value)).replace('\n', '<br/>'), style)

    def table(rows):
        count = len(rows[0])
        assert all(len(row) == count for row in rows), 'Malformed Markdown table'
        weights = {2: [0.34, 0.66], 3: [0.20, 0.27, 0.53],
                   4: [0.32, 0.14, 0.12, 0.42], 5: [0.12, 0.24, 0.12, 0.10, 0.42]}
        if count == 3 and rows[0][0] == '헤더':
            weights[3] = [0.36, 0.17, 0.47]
        widths = [WIDTH * w for w in weights.get(count, [1 / count] * count)]
        t = Table([[para(v, cell) for v in row] for row in rows],
                  colWidths=widths, repeatRows=1, hAlign='LEFT', splitByRow=len(rows) > 4)
        t.setStyle(TableStyle([
            ('BACKGROUND', (0, 0), (-1, 0), colors.HexColor('#e8f0f5')),
            ('VALIGN', (0, 0), (-1, -1), 'TOP'),
            ('LEFTPADDING', (0, 0), (-1, -1), 6),
            ('RIGHTPADDING', (0, 0), (-1, -1), 6),
            ('TOPPADDING', (0, 0), (-1, -1), 4),
            ('BOTTOMPADDING', (0, 0), (-1, -1), 4),
            ('LINEBELOW', (0, 0), (-1, 0), 0.6, colors.HexColor('#8ba6be')),
            ('LINEBELOW', (0, 1), (-1, -1), 0.25, colors.HexColor('#d5dee6')),
        ]))
        story.extend([t, Spacer(1, 9)])

    for source_index, source in enumerate(sources):
        if source_index:
            story.append(PageBreak())
        lines = source.read_text().splitlines()
        i = 0
        while i < len(lines):
            line = lines[i]
            if line.startswith('```'):
                language = line[3:]
                block = []
                i += 1
                while i < len(lines) and not lines[i].startswith('```'):
                    block.append(lines[i])
                    i += 1
                if language == 'mermaid':
                    diagram = '\n'.join(block)
                    directory = ROOT / 'tmp/pdfs/mermaid'
                    directory.mkdir(parents=True, exist_ok=True)
                    name = hashlib.sha256(diagram.encode()).hexdigest()[:12]
                    source_file = directory / (name + '.mmd')
                    image_file = directory / (name + '.png')
                    config_file = directory / 'config.json'
                    source_file.write_text(diagram)
                    config_file.write_text(json.dumps({
                        'theme': 'neutral',
                        'fontFamily': 'AppleGothic, sans-serif',
                        'sequence': {'wrap': True, 'width': 150, 'mirrorActors': False},
                    }))
                    subprocess.run([
                        'mmdc', '-i', str(source_file), '-o', str(image_file),
                        '-c', str(config_file), '-s', '3', '-q',
                    ], check=True)
                    image = Image(str(image_file))
                    image._restrictSize(WIDTH, 640)
                    story.extend([image, Spacer(1, 9)])
                else:
                    t = Table([[para('\n'.join(block), code)]], colWidths=[WIDTH])
                    t.setStyle(TableStyle([
                        ('BACKGROUND', (0, 0), (-1, -1), colors.HexColor('#f3f6f8')),
                        ('BOX', (0, 0), (-1, -1), 0.3, colors.HexColor('#d5dee6')),
                        ('LEFTPADDING', (0, 0), (-1, -1), 9),
                        ('RIGHTPADDING', (0, 0), (-1, -1), 9),
                        ('TOPPADDING', (0, 0), (-1, -1), 8),
                        ('BOTTOMPADDING', (0, 0), (-1, -1), 8),
                    ]))
                    story.extend([t, Spacer(1, 9)])
            elif line.startswith('|'):
                rows = []
                while i < len(lines) and lines[i].startswith('|'):
                    row = [v.strip() for v in lines[i].strip().strip('|').split('|')]
                    if not all(re.fullmatch(r'[-: ]+', v) for v in row):
                        rows.append(row)
                    i += 1
                table(rows)
                continue
            elif line.startswith('# '):
                story.append(para(line[2:], title))
            elif line.startswith('## '):
                story.append(para(line[3:], h2))
            elif line.startswith('### '):
                story.append(para(line[4:], h3))
            elif line.strip():
                story.append(para(line))
            i += 1

    revision = hashlib.sha256(b''.join(p.read_bytes() for p in sources)).hexdigest()[:10]

    def footer(canvas, doc):
        canvas.setStrokeColor(colors.HexColor('#cad7e3'))
        canvas.line(44, 39, A4[0] - 44, 39)
        canvas.setFont('Korean', 7)
        canvas.setFillColor(colors.HexColor('#63748a'))
        canvas.drawString(44, 26, label + ' | 2026-10-23 | ' + revision)
        canvas.drawRightString(A4[0] - 44, 26, str(doc.page))

    output.parent.mkdir(parents=True, exist_ok=True)
    SimpleDocTemplate(str(output), pagesize=A4, leftMargin=44, rightMargin=44,
                      topMargin=40, bottomMargin=53, title=label,
                      author='Chat Gateway').build(story, onFirstPage=footer, onLaterPages=footer)
    print(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--font', default='/System/Library/Fonts/Supplemental/AppleGothic.ttf')
    args = parser.parse_args()
    pdfmetrics.registerFont(TTFont('Korean', args.font))
    build([ROOT / 'docs/agent-api-gw-session-summary.md', ROOT / 'docs/unified-auth-spec.md'],
          ROOT / 'output/pdf/gateway-api-guide.pdf', 'Gateway API Guide')
    build([ROOT / 'docs/backend-integration-spec.md'],
          ROOT / 'output/pdf/backend-integration-spec.pdf', 'Backend Integration')
    build([ROOT / 'output/handover/chatbot-gateway-handover.md'],
          ROOT / 'output/handover/chatbot-gateway-handover.pdf', 'Gateway Handover')


if __name__ == '__main__':
    main()
