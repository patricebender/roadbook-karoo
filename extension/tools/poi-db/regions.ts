// Region catalog — the single source of truth for downloadable POI regions.
//
// Maps a stable `regionId` (used in filenames, the manifest, and the app's persisted
// selection) to its Geofabrik extract path(s) plus display metadata. Consumed by:
//   - build-region-file.sh (via `npx tsx regions.ts <id>` helpers) to resolve which
//     Geofabrik extract(s) to build,
//   - build-manifest.ts to emit dist/manifest.json,
//   - the app, as a generated assets/regions.json (label + group only), so the picker
//     renders without a network call.
//
// `geofabrik` is a path under https://download.geofabrik.de/ minus the `-latest.osm.pbf`
// suffix (e.g. "europe/germany/hessen"). A string[] means the region is built by merging
// several extracts (Germany Complete = all 16 Bundesländer), reusing the merge/dedup
// logic shared with build-multi-region.sh.
//
// `group` drives the picker's sections. Only Germany is broken down to Bundesland
// granularity; other countries are whole-country entries.

export interface Region {
  /** Stable id: `germany`, `germany-bayern`, `italy`, … Also the filename stem. */
  id: string;
  /** Display name shown in the picker. */
  label: string;
  /** Picker section. */
  group: "Germany" | "Europe";
  /** Geofabrik path(s) under download.geofabrik.de, minus `-latest.osm.pbf`. */
  geofabrik: string | string[];
}

/** The 16 German federal states, in the picker's display order (alphabetical by label). */
const GERMANY_STATES: Array<{ id: string; label: string; slug: string }> = [
  { id: "baden-wuerttemberg", label: "Baden-Württemberg", slug: "baden-wuerttemberg" },
  { id: "bayern", label: "Bayern", slug: "bayern" },
  { id: "berlin", label: "Berlin", slug: "berlin" },
  { id: "brandenburg", label: "Brandenburg", slug: "brandenburg" },
  { id: "bremen", label: "Bremen", slug: "bremen" },
  { id: "hamburg", label: "Hamburg", slug: "hamburg" },
  { id: "hessen", label: "Hessen", slug: "hessen" },
  { id: "mecklenburg-vorpommern", label: "Mecklenburg-Vorpommern", slug: "mecklenburg-vorpommern" },
  { id: "niedersachsen", label: "Niedersachsen", slug: "niedersachsen" },
  { id: "nordrhein-westfalen", label: "Nordrhein-Westfalen", slug: "nordrhein-westfalen" },
  { id: "rheinland-pfalz", label: "Rheinland-Pfalz", slug: "rheinland-pfalz" },
  { id: "saarland", label: "Saarland", slug: "saarland" },
  { id: "sachsen", label: "Sachsen", slug: "sachsen" },
  { id: "sachsen-anhalt", label: "Sachsen-Anhalt", slug: "sachsen-anhalt" },
  { id: "schleswig-holstein", label: "Schleswig-Holstein", slug: "schleswig-holstein" },
  { id: "thueringen", label: "Thüringen", slug: "thueringen" },
];

/** Whole-country regions (no sub-region breakdown). */
const COUNTRIES: Array<{ id: string; label: string; geofabrik: string }> = [
  { id: "italy", label: "Italy", geofabrik: "europe/italy" },
  { id: "switzerland", label: "Switzerland", geofabrik: "europe/switzerland" },
  { id: "austria", label: "Austria", geofabrik: "europe/austria" },
  { id: "france", label: "France", geofabrik: "europe/france" },
  { id: "netherlands", label: "Netherlands", geofabrik: "europe/netherlands" },
  { id: "belgium", label: "Belgium", geofabrik: "europe/belgium" },
  { id: "denmark", label: "Denmark", geofabrik: "europe/denmark" },
  { id: "norway", label: "Norway", geofabrik: "europe/norway" },
  { id: "sweden", label: "Sweden", geofabrik: "europe/sweden" },
];

export const REGIONS: Region[] = [
  // Germany Complete — merge of all 16 Bundesland extracts.
  {
    id: "germany",
    label: "Germany (Complete)",
    group: "Germany",
    geofabrik: GERMANY_STATES.map((s) => `europe/germany/${s.slug}`),
  },
  // One entry per Bundesland.
  ...GERMANY_STATES.map((s): Region => ({
    id: `germany-${s.id}`,
    label: s.label,
    group: "Germany",
    geofabrik: `europe/germany/${s.slug}`,
  })),
  // Whole countries.
  ...COUNTRIES.map((c): Region => ({
    id: c.id,
    label: c.label,
    group: "Europe",
    geofabrik: c.geofabrik,
  })),
];

/** Look up a region by id, or throw a helpful error listing valid ids. */
export function regionById(id: string): Region {
  const r = REGIONS.find((x) => x.id === id);
  if (!r) {
    throw new Error(
      `unknown regionId "${id}". Valid ids: ${REGIONS.map((x) => x.id).join(", ")}`,
    );
  }
  return r;
}

/** Resolve a region's Geofabrik path(s) as an array (single-path regions → length 1). */
export function geofabrikPaths(r: Region): string[] {
  return Array.isArray(r.geofabrik) ? r.geofabrik : [r.geofabrik];
}

// CLI: `npx tsx regions.ts <command> [id]`
//   ids                 → print all region ids, one per line
//   paths <id>          → print the region's Geofabrik path(s), space-separated
//   app-json            → print the app-facing regions.json ({id,label,group}[])
// Kept tiny so the bash builder can shell out for exactly what it needs.
if (import.meta.url === `file://${process.argv[1]}`) {
  const [cmd, arg] = process.argv.slice(2);
  switch (cmd) {
    case "ids":
      console.log(REGIONS.map((r) => r.id).join("\n"));
      break;
    case "paths":
      if (!arg) throw new Error("usage: regions.ts paths <id>");
      console.log(geofabrikPaths(regionById(arg)).join(" "));
      break;
    case "app-json":
      console.log(
        JSON.stringify(
          REGIONS.map(({ id, label, group }) => ({ id, label, group })),
          null,
          2,
        ),
      );
      break;
    default:
      throw new Error("usage: regions.ts <ids|paths <id>|app-json>");
  }
}
