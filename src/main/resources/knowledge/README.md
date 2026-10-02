# Knowledge base

Curated agronomy and maintenance notes the farm assistant retrieves from (RAG). Each document is
written for IrrigaIQ in our own words and cites the published sources it summarises. They are
general guidance: the assistant always pairs them with the farm's own data and never presents
them as a substitute for a local agronomist or pump technician.

Format: YAML front matter (`title`, `sources` with `title`, `publisher`, optional `url`), then
`##` sections. Each section becomes one or more retrieval chunks, so keep sections focused on one
question. Documents are re-indexed on startup whenever their content changes. This README is not
indexed.
