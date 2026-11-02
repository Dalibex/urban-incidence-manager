// Generates UrbanPulse-C4.drawio from workspace.dsl, one page per Structurizr view.
//
// 1. Structurizr exports the workspace as JSON: the model, the views and their styles, but no
//    positions, because autoLayout is computed by whoever renders a view.
// 2. Graphviz lays out each view in the rank direction of its autoLayout, as Structurizr does: it
//    places the boxes, routes every arrow around them and keeps room for each arrow's label.
// 3. This script draws the result in the style of the model: a card per element with a coloured
//    header, a line with type, technology, status and ADR, and the description; a box per boundary
//    or deployment node; and the arrows through Graphviz's points, labelled with their description
//    and technology.
//
// The output depends only on the DSL and the pinned images, so two runs give the same file and its
// diff shows what changed in the model. It runs inside the image of drawio/Dockerfile.

import { execFileSync } from 'node:child_process';
import { mkdtempSync, readdirSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const WORKSPACE_DSL = 'workspace.dsl';
const OUTPUT = 'UrbanPulse-C4.drawio';
const STRUCTURIZR_WAR = '/opt/structurizr/structurizr.war';

const FONT = 'Helvetica';
const CARD_WIDTH = 260;
const CARD_HEADER = 28;
const LINE_HEIGHT = 15;
// Average width of a character, measured on the descriptions of this model: 11px body text and
// 10px upper-case metadata with letter spacing. They only decide where lines wrap.
const BODY_CHAR_WIDTH = 6.1;
const META_CHAR_WIDTH = 6.4;
const BODY_PADDING = 22;
const EDGE_LABEL_CHARS = 26;
const BOX_HEADER_CHARS = 44;
// Space between ranks and between boxes of a rank, in pixels. Graphviz adds the room of the labels.
const RANK_SEPARATION = 60;
const NODE_SEPARATION = 40;
const PAGE_TOP = 110;

const USER_INTERACTION_COLOR = '#9C7AB8';
const TEXT_COLOR = '#1F2937';
const MUTED_COLOR = '#64748B';
const BOUNDARY_STYLE = {
    fill: '#EDF4FC',
    stroke: '#8DB0DB',
    font: '#5B87C2',
};

// Legend entries, in order. Each one appears only on the pages that have an element of its kind.
const LEGEND_ELEMENTS = [
    { label: 'person', matches: (card) => card.type === 'Person' },
    {
        label: 'UrbanPulse',
        matches: (card) =>
            ['System', 'Backend', 'FrontendWeb', 'FrontendMobile', 'ReverseProxy', 'AdminProcess'].some(
                (tag) => card.tags.includes(tag),
            ) ||
            (card.type === 'Component' && card.tags.at(-1) === 'Component'),
    },
    {
        label: 'module',
        matches: (card) =>
            ['Identity', 'Incidents', 'UrbanContext', 'Geospatial', 'Notifications', 'Analytics'].some(
                (tag) => card.tags.includes(tag),
            ),
    },
    { label: 'data store', matches: (card) => card.tags.includes('Database') || card.tags.includes('Storage') },
    { label: 'cache', matches: (card) => card.tags.includes('Cache') },
    { label: 'infrastructure', matches: (card) => card.type === 'InfrastructureNode' },
    { label: 'external system', matches: (card) => card.tags.includes('ExternalAPI') && !card.tags.includes('Future') },
];

main();

function main() {
    execFileSync('dot', ['-V'], { stdio: 'inherit' });
    const workspace = exportWorkspace();
    const model = indexModel(workspace.model);
    const styles = workspace.views.configuration?.styles ?? {};
    const pages = collectViews(workspace.views).map((view, index) =>
        drawPage(view, index, model, styles),
    );
    writeFileSync(OUTPUT, mxfile(pages));
    console.log(`Wrote ${OUTPUT} with ${pages.length} pages.`);
}

// ---------------------------------------------------------------------------------------------
// Workspace

function exportWorkspace() {
    const outputDir = mkdtempSync(join(tmpdir(), 'c4-'));
    execFileSync(
        'java',
        [
            '-Dio.netty.noUnsafe=true',
            '--enable-native-access=ALL-UNNAMED',
            '-jar',
            STRUCTURIZR_WAR,
            'export',
            '-w',
            WORKSPACE_DSL,
            '-f',
            'json',
            '-o',
            outputDir,
        ],
        // Structurizr logs to stdout; keep it on stderr so a failure shows why.
        { stdio: ['ignore', process.stderr, 'inherit'] },
    );
    const json = readdirSync(outputDir).find((file) => file.endsWith('.json'));
    return JSON.parse(readFileSync(join(outputDir, json), 'utf8'));
}

function indexModel(model) {
    const elements = new Map();
    const relationships = new Map();
    // Cells are named after the DSL identifier (urbanpulse.backend), not Structurizr's numeric
    // id, which renumbers every element after one that is inserted, so a diff shows only real
    // changes.
    const add = (element, type, parentId) => {
        const key = element.properties?.['structurizr.dsl.identifier'] ?? element.id;
        elements.set(element.id, { ...element, type, parentId, key });
        for (const relationship of element.relationships ?? []) {
            relationships.set(relationship.id, relationship);
        }
    };
    const addDeploymentNode = (node, parentId) => {
        add(node, 'DeploymentNode', parentId);
        for (const child of node.children ?? []) addDeploymentNode(child, node.id);
        for (const infrastructure of node.infrastructureNodes ?? []) {
            add(infrastructure, 'InfrastructureNode', node.id);
        }
        for (const instance of node.containerInstances ?? []) add(instance, 'ContainerInstance', node.id);
        for (const instance of node.softwareSystemInstances ?? []) {
            add(instance, 'SoftwareSystemInstance', node.id);
        }
    };

    for (const person of model.people ?? []) add(person, 'Person');
    for (const system of model.softwareSystems ?? []) {
        add(system, 'SoftwareSystem');
        for (const container of system.containers ?? []) {
            add(container, 'Container', system.id);
            for (const component of container.components ?? []) {
                add(component, 'Component', container.id);
            }
        }
    }
    for (const node of model.deploymentNodes ?? []) addDeploymentNode(node);
    return { elements, relationships };
}

function collectViews(views) {
    const kinds = {
        systemContextViews: 'systemContext',
        containerViews: 'container',
        componentViews: 'component',
        dynamicViews: 'dynamic',
        deploymentViews: 'deployment',
    };
    return Object.entries(kinds)
        .flatMap(([key, kind]) => (views[key] ?? []).map((view) => ({ ...view, kind })))
        .sort((a, b) => a.order - b.order);
}

function tagsOf(item) {
    return (item.tags ?? '')
        .split(',')
        .map((tag) => tag.trim())
        .filter(Boolean);
}

// A container instance is drawn as its container, with the tags of both.
function resolveElement(id, model) {
    const element = model.elements.get(id);
    const source =
        element.type === 'ContainerInstance'
            ? model.elements.get(element.containerId)
            : element.type === 'SoftwareSystemInstance'
              ? model.elements.get(element.softwareSystemId)
              : element;
    return {
        id,
        key: element.key,
        type: element.type === 'ContainerInstance' ? 'Container' : element.type,
        parentId: element.parentId,
        name: source.name,
        description: source.description ?? '',
        technology: source.technology ?? element.technology ?? '',
        url: source.url ?? '',
        tags: [...tagsOf(source), ...tagsOf(element)],
    };
}

function mergeStyles(tags, styleList = []) {
    const merged = {};
    for (const tag of tags) {
        for (const style of styleList) {
            if (style.tag === tag) Object.assign(merged, style);
        }
    }
    return merged;
}

// ---------------------------------------------------------------------------------------------
// One view

function drawPage(view, index, model, styles) {
    const cards = new Map();
    const boxes = new Map();
    for (const { id } of view.elements) {
        const element = model.elements.get(id);
        if (element.type === 'DeploymentNode') {
            boxes.set(id, deploymentNodeBox(element, view, model));
        } else {
            cards.set(id, card(resolveElement(id, model), styles));
        }
    }
    addBoundary(view, model, cards, boxes);
    for (const item of [...cards.values(), ...boxes.values()]) {
        if (item.parentBox === undefined && boxes.has(item.parentId)) item.parentBox = item.parentId;
    }

    for (const item of [...cards.values(), ...boxes.values()]) item.cell = `${view.key}.${item.key}`;

    const edges = viewRelationships(view, model).map((relationship) =>
        edge(relationship, view, model, styles),
    );
    nameEdges(view, edges, cards, boxes);
    const rankdir = view.automaticLayout?.rankDirection === 'TopBottom' ? 'TB' : 'LR';
    layout(rankdir, cards, boxes, edges);
    checkLayout(view, cards, boxes, edges);

    const cells = [
        ...pageHeader(view, index, cards, edges),
        ...[...boxes.values()].sort((a, b) => a.depth - b.depth).map(boxCell),
        ...edges.map(edgeCell),
        ...[...cards.values()].flatMap(cardCells),
    ];
    return { id: view.key, name: `${index + 1} · ${view.title ?? view.key}`, cells };
}

// An arrow is named after its ends, or after its step in a dynamic view. A second arrow between the
// same ends gets a number.
function nameEdges(view, edges, cards, boxes) {
    const cellOf = (id) => (cards.get(id) ?? boxes.get(id)).cell;
    const used = new Map();
    for (const item of edges) {
        item.sourceCell = cellOf(item.sourceId);
        item.targetCell = cellOf(item.targetId);
        const name =
            view.kind === 'dynamic'
                ? `${view.key}.step${item.order}`
                : `${item.sourceCell}->${item.targetCell.slice(view.key.length + 1)}`;
        const count = (used.get(name) ?? 0) + 1;
        used.set(name, count);
        item.id = count === 1 ? name : `${name}#${count}`;
    }
}

// Graphviz warns but does not fail when it cannot place something; a file with holes must not be
// written.
function checkLayout(view, cards, boxes, edges) {
    const missing = [
        ...[...cards.values(), ...boxes.values()].filter((item) => item.x === undefined).map((item) => item.key),
        ...edges.filter((item) => item.path === undefined).map((item) => item.id),
    ];
    if (missing.length > 0) {
        throw new Error(`View ${view.key}: Graphviz returned no position for ${missing.join(', ')}`);
    }
}

function card(element, styles) {
    const style = mergeStyles(element.tags, styles.elements);
    const status = element.tags.includes('Future')
        ? 'Future'
        : element.tags.includes('Proposed')
          ? 'Proposed'
          : '';
    const adr = element.url.match(/\/adr\/(\d{4})-/)?.[1];
    const meta = [typeLabel(element), element.technology, status, adr && `ADR-${adr}`]
        .filter(Boolean)
        .join(' · ')
        .toUpperCase();
    const textWidth = CARD_WIDTH - BODY_PADDING;
    const metaLines = wrap(meta, Math.floor(textWidth / META_CHAR_WIDTH)).length;
    const bodyLines = wrap(element.description, Math.floor(textWidth / BODY_CHAR_WIDTH)).length;
    return {
        ...element,
        meta,
        status,
        color: style.background ?? MUTED_COLOR,
        fontColor: style.color ?? '#FFFFFF',
        border: (style.border ?? '').toLowerCase(),
        opacity: style.opacity,
        width: CARD_WIDTH,
        height: CARD_HEADER + 6 + LINE_HEIGHT * (metaLines + bodyLines) + 12,
    };
}

function typeLabel(element) {
    switch (element.type) {
        case 'Person':
            return 'Person';
        case 'SoftwareSystem':
            return element.tags.includes('ExternalAPI') ? 'External system' : 'Software system';
        case 'InfrastructureNode':
            return 'Infrastructure node';
        default:
            return element.type;
    }
}

function deploymentNodeBox(node, view, model) {
    const title = node.technology ? `${node.name}  [${node.technology}]` : node.name;
    const descriptionLines = wrap(node.description ?? '', BOX_HEADER_CHARS);
    return {
        id: node.id,
        key: node.key,
        kind: 'deploymentNode',
        parentId: node.parentId,
        name: node.name,
        technology: node.technology ?? '',
        descriptionLines,
        headerLines: [title, ...descriptionLines],
        depth: depthOf(node, model),
    };
}

function depthOf(node, model) {
    let depth = 0;
    for (let parent = node.parentId; parent !== undefined; parent = model.elements.get(parent).parentId) {
        depth++;
    }
    return depth;
}

// Container, component and dynamic views draw their scope as a dashed boundary around the
// elements that belong to it.
function addBoundary(view, model, cards, boxes) {
    const scopeId = view.softwareSystemId ?? view.containerId ?? view.elementId;
    if (view.kind === 'systemContext' || view.kind === 'deployment' || scopeId === undefined) return;
    const scope = model.elements.get(scopeId);
    const id = `boundary-${scopeId}`;
    boxes.set(id, {
        id,
        key: `boundary.${scope.key}`,
        kind: 'boundary',
        name: `${scope.name}  [${scope.type === 'SoftwareSystem' ? 'Software system' : 'Container'}]`,
        headerLines: [scope.name],
        depth: 0,
    });
    for (const item of cards.values()) {
        if (item.parentId === scopeId) item.parentBox = id;
    }
}

function viewRelationships(view, model) {
    const relationships = view.relationships.map((entry) => {
        const relationship = model.relationships.get(entry.id);
        const linked = relationship.linkedRelationshipId
            ? model.relationships.get(relationship.linkedRelationshipId)
            : undefined;
        return {
            ...relationship,
            tags: relationship.tags ?? linked?.tags,
            order: entry.order,
            response: entry.response === true,
            description: entry.description ?? relationship.description ?? '',
        };
    });
    if (view.kind === 'dynamic') {
        relationships.sort((a, b) => a.order.localeCompare(b.order, 'en', { numeric: true }));
    }
    return relationships;
}

function edge(relationship, view, model, styles) {
    // A response in a dynamic view uses the relationship the other way round.
    const [sourceId, targetId] = relationship.response
        ? [relationship.destinationId, relationship.sourceId]
        : [relationship.sourceId, relationship.destinationId];
    const tags = tagsOf(relationship);
    const style = mergeStyles(tags.length > 0 ? tags : ['Relationship'], styles.relationships);
    const lineStyle = (style.style ?? (style.dashed ? 'Dashed' : 'Solid')).toLowerCase();
    const fromPerson = model.elements.get(relationship.sourceId).type === 'Person';
    const prefix = view.kind === 'dynamic' ? `${relationship.order}. ` : '';
    return {
        order: relationship.order,
        sourceId,
        targetId,
        descriptionLines: wrap(prefix + relationship.description, EDGE_LABEL_CHARS),
        technology: relationship.technology ?? '',
        color: fromPerson && !relationship.response ? USER_INTERACTION_COLOR : (style.color ?? MUTED_COLOR),
        line: relationship.response ? 'dashed' : lineStyle,
        async: tags.includes('Async'),
        future: tags.includes('Future'),
        response: relationship.response,
    };
}

// ---------------------------------------------------------------------------------------------
// Layout with Graphviz

function layout(rankdir, cards, boxes, edges) {
    const leafOf = (id) => {
        const leaf = cards.has(id) ? id : firstLeaf(id, cards, boxes);
        if (leaf === undefined) throw new Error(`An arrow ends at ${boxes.get(id).key}, which has nothing inside`);
        return leaf;
    };
    const lines = [
        'digraph G {',
        `graph [rankdir=${rankdir}, ranksep=${inches(RANK_SEPARATION)}, nodesep=${inches(NODE_SEPARATION)}, splines=polyline, compound=true, newrank=true, fontname="DejaVu Sans", fontsize=14];`,
        'node [shape=box, fixedsize=true, label=""];',
        // DejaVu is wider than the Helvetica of draw.io, so the room kept for a label is enough.
        'edge [fontname="DejaVu Sans", fontsize=11];',
    ];
    const emitBox = (box, indent) => {
        lines.push(`${indent}subgraph "cluster_${box.id}" {`);
        lines.push(`${indent}  graph [label="${dotEscape(box.headerLines.join('\n'))}", margin=24];`);
        for (const child of boxes.values()) if (child.parentBox === box.id) emitBox(child, `${indent}  `);
        for (const item of cards.values()) if (item.parentBox === box.id) emitCard(item, `${indent}  `);
        lines.push(`${indent}}`);
    };
    const emitCard = (item, indent) =>
        lines.push(`${indent}"${item.id}" [width=${inches(item.width)}, height=${inches(item.height)}];`);

    for (const box of boxes.values()) if (box.parentBox === undefined) emitBox(box, '  ');
    for (const item of cards.values()) if (item.parentBox === undefined) emitCard(item, '  ');
    for (const item of edges) {
        // A response is laid out as the request it answers, so the steps of a dynamic view run in
        // one direction; its route is turned round when it is read back.
        const [sourceId, targetId] = item.response
            ? [item.targetId, item.sourceId]
            : [item.sourceId, item.targetId];
        const label = [...item.descriptionLines, item.technology && `[${item.technology}]`].filter(Boolean);
        const attributes = [
            `id="${dotEscape(item.id)}"`,
            `label="${dotEscape(label.join('\n'))}"`,
            boxes.has(sourceId) && `ltail="cluster_${sourceId}"`,
            boxes.has(targetId) && `lhead="cluster_${targetId}"`,
            // People go first, as in any C4 diagram. An arrow that reaches a person (a provider
            // that delivers an email) would pull them to the last rank, so it does not rank them.
            cards.get(targetId)?.type === 'Person' && 'constraint=false',
        ].filter(Boolean);
        lines.push(`  "${leafOf(sourceId)}" -> "${leafOf(targetId)}" [${attributes.join(', ')}];`);
    }
    lines.push('}');

    const result = JSON.parse(execFileSync('dot', ['-Tjson'], { input: lines.join('\n') }).toString());
    const [, , , graphHeight] = result.bb.split(',').map(Number);
    for (const object of result.objects ?? []) {
        if (object.name.startsWith('cluster_')) {
            const [left, bottom, right, top] = object.bb.split(',').map(Number);
            Object.assign(boxes.get(object.name.slice('cluster_'.length)), {
                x: Math.round(left),
                y: Math.round(graphHeight - top) + PAGE_TOP,
                width: Math.round(right - left),
                height: Math.round(top - bottom),
            });
        } else if (cards.has(object.name)) {
            const item = cards.get(object.name);
            const [x, y] = object.pos.split(',').map(Number);
            item.x = Math.round(x - item.width / 2);
            item.y = Math.round(graphHeight - y - item.height / 2) + PAGE_TOP;
        }
    }
    const toPage = ([x, y]) => [Math.round(x), Math.round(graphHeight - y) + PAGE_TOP];
    const byId = new Map(edges.map((item) => [item.id, item]));
    for (const routed of result.edges ?? []) {
        const item = byId.get(routed.id);
        // pos is a B-spline: an optional arrow end ("e,x,y"), then the start point and three
        // points per segment. With polyline splines every third point is a bend.
        const parts = routed.pos.split(' ');
        const end = parts[0].startsWith('e,') ? parts.shift().slice(2) : undefined;
        if (parts[0].startsWith('s,')) parts.shift();
        const path = parts
            .map((point) => point.split(',').map(Number))
            .filter((_, i) => i % 3 === 0);
        if (end) path.push(end.split(',').map(Number));
        item.path = path.map(toPage);
        if (item.response) item.path.reverse();
        item.labelPoint = routed.lp ? toPage(routed.lp.split(',').map(Number)) : undefined;
    }
}

function firstLeaf(boxId, cards, boxes) {
    for (const item of cards.values()) if (item.parentBox === boxId) return item.id;
    for (const box of boxes.values()) {
        if (box.parentBox === boxId) {
            const leaf = firstLeaf(box.id, cards, boxes);
            if (leaf) return leaf;
        }
    }
    return undefined;
}

// ---------------------------------------------------------------------------------------------
// draw.io cells

function pageHeader(view, index, cards, edges) {
    const key = view.key;
    const legend = pageLegend(view, cards, edges);
    const legendWidth = Math.round(legend.text.length * 6.3 + 40);
    return [
        vertex(
            `${key}.title`,
            `UrbanPulse · ${escapeHtml(view.title ?? view.key)}`,
            `text;html=1;whiteSpace=wrap;align=left;verticalAlign=middle;fontFamily=${FONT};fontSize=26;fontColor=#0F172A;fontStyle=1;spacing=0;`,
            { x: 0, y: 0, width: 1100, height: 34 },
        ),
        vertex(
            `${key}.subtitle`,
            escapeHtml(view.description ?? ''),
            `text;html=1;whiteSpace=wrap;align=left;verticalAlign=middle;fontFamily=${FONT};fontSize=13;fontColor=${MUTED_COLOR};spacing=0;`,
            { x: 0, y: 36, width: 1100, height: 20 },
        ),
        vertex(
            `${key}.legend`,
            legend.html,
            `rounded=1;arcSize=12;html=1;whiteSpace=wrap;fillColor=#FFFFFF;strokeColor=#D0D7DE;fontFamily=${FONT};fontSize=12;fontColor=#475569;align=center;verticalAlign=middle;`,
            { x: 0, y: 64, width: legendWidth, height: 30 },
        ),
    ];
}

// Each entry is plain text with an optional mark, coloured or not. The HTML and the width of the box
// are both built from it, so nothing has to strip tags out of HTML to measure it.
function pageLegend(view, cards, edges) {
    const all = [...cards.values()];
    const swatches = LEGEND_ELEMENTS.flatMap(({ label, matches }) => {
        const match = all.find(matches);
        return match ? [{ mark: '■', color: match.color, text: label }] : [];
    });
    const lines = [
        edges.some((item) => item.color === USER_INTERACTION_COLOR) && {
            mark: '──',
            color: USER_INTERACTION_COLOR,
            text: 'user interaction',
        },
        { mark: '──', text: 'call' },
        edges.some((item) => item.async) && { mark: '- - -', text: 'async call' },
        edges.some((item) => item.response) && { mark: '- - -', text: 'response' },
        view.kind === 'dynamic' && { text: 'numbers = order of the steps' },
    ].filter(Boolean);
    const boxes = [
        all.some((item) => item.status === 'Proposed') && { text: 'dashed box = proposed ADR' },
        (all.some((item) => item.status === 'Future') || edges.some((item) => item.future)) && {
            text: 'dotted = future',
        },
        view.kind === 'deployment' && { text: 'outer boxes = where it runs [technology]' },
        all.some((item) => item.url) && { text: 'click a box to open its ADR' },
    ].filter(Boolean);
    const groups = [swatches, lines, boxes].filter((group) => group.length > 0);
    const entryHtml = ({ mark, color, text }) =>
        [mark && (color ? `<font color="${color}">${mark}</font>` : mark), escapeHtml(text)]
            .filter(Boolean)
            .join(' ');
    const entryText = ({ mark, text }) => [mark, text].filter(Boolean).join(' ');
    return {
        html: groups.map((group) => group.map(entryHtml).join(' &nbsp; ')).join(' &nbsp;|&nbsp; '),
        // The same spacing as the HTML, where &nbsp; is one character.
        text: groups.map((group) => group.map(entryText).join('   ')).join('  |  '),
    };
}

function cardCells(item) {
    const dash =
        item.border === 'dotted' ? 'dashed=1;dashPattern=1 3;' : item.border === 'dashed' ? 'dashed=1;dashPattern=6 4;' : '';
    const opacity = item.opacity !== undefined && item.opacity < 100 ? `opacity=${item.opacity};` : '';
    const header = vertex(
        item.cell,
        escapeHtml(item.name),
        `swimlane;html=1;whiteSpace=wrap;rounded=1;arcSize=3;startSize=${CARD_HEADER};fillColor=${item.color};swimlaneFillColor=#FFFFFF;strokeColor=${item.color};fontColor=${item.fontColor};fontFamily=${FONT};fontSize=13;fontStyle=1;collapsible=0;swimlaneLine=0;${dash}${opacity}`,
        item,
        item.url,
    );
    const body =
        `<div style="color:${MUTED_COLOR};font-size:10px;line-height:${LINE_HEIGHT}px;letter-spacing:0.3px;">${escapeHtml(item.meta)}</div>` +
        `<div style="line-height:${LINE_HEIGHT}px;">${escapeHtml(item.description)}</div>`;
    return [
        header,
        vertex(
            `${item.cell}.body`,
            body,
            `text;html=1;whiteSpace=wrap;align=left;verticalAlign=top;fontFamily=${FONT};fontSize=11;fontColor=${TEXT_COLOR};spacingLeft=12;spacingRight=10;spacingTop=6;connectable=0;`,
            { x: 0, y: CARD_HEADER, width: item.width, height: item.height - CARD_HEADER },
            undefined,
            item.cell,
        ),
    ];
}

function boxCell(box) {
    if (box.kind === 'boundary') {
        return vertex(
            box.cell,
            escapeHtml(box.name),
            `swimlane;html=1;whiteSpace=wrap;rounded=1;arcSize=1;dashed=1;dashPattern=6 4;startSize=30;fillColor=${BOUNDARY_STYLE.fill};swimlaneFillColor=${BOUNDARY_STYLE.fill};strokeColor=${BOUNDARY_STYLE.stroke};fontColor=${BOUNDARY_STYLE.font};fontFamily=${FONT};fontSize=15;fontStyle=1;collapsible=0;container=1;`,
            box,
        );
    }
    const title =
        `<b>${escapeHtml(box.name)}</b>` +
        (box.technology ? ` <font color="${MUTED_COLOR}">[${escapeHtml(box.technology)}]</font>` : '');
    const description = box.descriptionLines.length
        ? `<br><font style="font-size:11px" color="${MUTED_COLOR}">${escapeHtml(box.descriptionLines.join(' '))}</font>`
        : '';
    const fill = box.depth % 2 === 0 ? '#F8FAFC' : '#FFFFFF';
    return vertex(
        box.cell,
        title + description,
        `swimlane;html=1;whiteSpace=wrap;rounded=1;arcSize=2;startSize=${14 + 17 * box.headerLines.length};fillColor=${fill};swimlaneFillColor=${fill};strokeColor=#94A3B8;fontColor=#0F172A;fontFamily=${FONT};fontSize=13;fontStyle=0;align=left;spacingLeft=10;verticalAlign=top;spacingTop=4;collapsible=0;swimlaneLine=0;container=1;`,
        box,
    );
}

function edgeCell(item) {
    const label =
        item.descriptionLines.map(escapeHtml).join('<br>') +
        (item.technology
            ? `<br><font style="font-size:10px" color="${MUTED_COLOR}">[${escapeHtml(item.technology)}]</font>`
            : '');
    const dash =
        item.line === 'dotted' ? 'dashed=1;dashPattern=1 3;' : item.line === 'dashed' ? 'dashed=1;dashPattern=6 4;' : '';
    const style =
        `edgeStyle=none;rounded=1;arcSize=20;html=1;strokeColor=${item.color};strokeWidth=1.3;endArrow=blockThin;endFill=1;endSize=7;${dash}` +
        `fontFamily=${FONT};fontSize=11;fontColor=#374151;labelBackgroundColor=#FFFFFF;spacing=2;`;
    // draw.io joins the two boxes through the bends of Graphviz's route and puts the label halfway,
    // so an offset moves it to where Graphviz kept room for it.
    const points = item.path
        .slice(1, -1)
        .map(([x, y]) => `<mxPoint x="${x}" y="${y}" />`)
        .join('');
    const [midX, midY] = midpoint(item.path);
    const [labelX, labelY] = item.labelPoint ?? [midX, midY];
    return (
        `<mxCell id="${escapeXml(item.id)}" value="${escapeXml(label)}" style="${escapeXml(style)}" edge="1" parent="1" source="${escapeXml(item.sourceCell)}" target="${escapeXml(item.targetCell)}">` +
        '<mxGeometry relative="1" as="geometry">' +
        (points ? `<Array as="points">${points}</Array>` : '') +
        `<mxPoint x="${Math.round(labelX - midX)}" y="${Math.round(labelY - midY)}" as="offset" />` +
        '</mxGeometry></mxCell>'
    );
}

// The point halfway along a polyline.
function midpoint(path) {
    const lengths = path.slice(1).map(([x, y], i) => Math.hypot(x - path[i][0], y - path[i][1]));
    let remaining = lengths.reduce((sum, length) => sum + length, 0) / 2;
    for (let i = 0; i < lengths.length; i++) {
        if (remaining <= lengths[i] && lengths[i] > 0) {
            const t = remaining / lengths[i];
            return [
                path[i][0] + t * (path[i + 1][0] - path[i][0]),
                path[i][1] + t * (path[i + 1][1] - path[i][1]),
            ];
        }
        remaining -= lengths[i];
    }
    return path[0];
}

function vertex(id, value, style, geometry, link, parent = '1') {
    const { x, y, width, height } = geometry;
    const cell =
        `<mxCell style="${escapeXml(style)}" vertex="1" parent="${escapeXml(parent)}">` +
        `<mxGeometry x="${x}" y="${y}" width="${width}" height="${height}" as="geometry" /></mxCell>`;
    if (link) {
        return `<UserObject label="${escapeXml(value)}" link="${escapeXml(link)}" id="${escapeXml(id)}">${cell}</UserObject>`;
    }
    return cell.replace('<mxCell ', `<mxCell id="${escapeXml(id)}" value="${escapeXml(value)}" `);
}

function mxfile(pages) {
    const diagrams = pages.map(
        (page) =>
            `  <diagram id="${escapeXml(page.id)}" name="${escapeXml(page.name)}">\n` +
            '    <mxGraphModel grid="1" gridSize="10" guides="1" tooltips="1" connect="1" arrows="1" fold="1" page="0" pageScale="1" background="#FFFFFF" math="0" shadow="0">\n' +
            '      <root>\n        <mxCell id="0" />\n        <mxCell id="1" parent="0" />\n' +
            page.cells.map((cell) => `        ${cell}\n`).join('') +
            '      </root>\n    </mxGraphModel>\n  </diagram>\n',
    );
    // draw.io keeps the creator in `agent` and refuses a file that does not start with <mxfile>, so
    // the warning goes there instead of in an XML comment.
    const agent =
        'Generated from workspace.dsl by drawio/generate.mjs. Do not edit: change the DSL and run ' +
        'docker compose -f docs/architecture/compose.yaml run --rm drawio';
    return `<mxfile agent="${escapeXml(agent)}" pages="${pages.length}">\n${diagrams.join('')}</mxfile>\n`;
}

// ---------------------------------------------------------------------------------------------
// Text

function wrap(text, maxChars) {
    const lines = [];
    let line = '';
    for (const word of text.split(/\s+/).filter(Boolean)) {
        if (line && line.length + 1 + word.length > maxChars) {
            lines.push(line);
            line = word;
        } else {
            line = line ? `${line} ${word}` : word;
        }
    }
    if (line) lines.push(line);
    return lines;
}

function inches(pixels) {
    return Number((pixels / 72).toFixed(4));
}

function dotEscape(text) {
    return text.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n');
}

function escapeHtml(text) {
    return text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

function escapeXml(text) {
    return String(text)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/\n/g, '&#10;');
}
