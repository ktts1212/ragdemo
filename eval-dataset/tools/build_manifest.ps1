# Step 5 Stage 5.0 - deterministic registration (ASCII only; no LLM; inputs read-only).
# Reproducible re-run: outputs eval-dataset/manifest.json and eval-dataset/run_config.json
# as UTF-8 WITHOUT BOM. paper_id = "sha256:" + first 16 hex chars of the file SHA-256.
# page_count is a disclosed heuristic (count of "/Type /Page" not followed by 's');
# page_count_verified stays false until a real page count is obtained.
# document_id / knowledge_base_id stay null until mapped to ingested documents (no guessing).

$ErrorActionPreference = "Stop"
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$pdfDir = Join-Path $repoRoot "src\test\resources\pdfs"
$outDir = Join-Path $repoRoot "eval-dataset"
New-Item -ItemType Directory -Force $outDir | Out-Null
if (-not (Test-Path $pdfDir)) { throw "PDF dir not found: $pdfDir" }

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)

$papers = @()
foreach ($f in Get-ChildItem $pdfDir -Filter *.pdf | Sort-Object Name) {
    $sha = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256).Hash.ToLower()
    $latin = [System.Text.Encoding]::GetEncoding(28591).GetString([System.IO.File]::ReadAllBytes($f.FullName))
    $pages = ([regex]::Matches($latin, '/Type\s*/Page[^s]')).Count
    $papers += [ordered]@{
        paper_id            = "sha256:" + $sha.Substring(0, 16)
        file_name           = $f.Name
        pdf_sha256          = $sha
        bytes               = $f.Length
        page_count          = $pages
        page_count_method   = "heuristic_regex(/Type /Page)"
        page_count_verified = $false
        document_id         = $null
        knowledge_base_id   = $null
        ingestion_status    = "UNKNOWN"
        read_status         = "REGISTERED"
        processed           = $false
        questions_final     = 0
        notes               = @()
    }
}

$manifest = [ordered]@{
    schema_version  = "1.0"
    dataset_version = "v1"
    generated_at    = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
    pdf_input_dir   = "src/test/resources/pdfs"
    paper_count     = $papers.Count
    content_dedup   = "by pdf_sha256"
    papers          = $papers
}
[System.IO.File]::WriteAllText((Join-Path $outDir "manifest.json"), ($manifest | ConvertTo-Json -Depth 8),
    $utf8NoBom)

$runConfig = [ordered]@{
    schema_version             = "1.0"
    dataset_version            = "v1"
    stage                      = "5.0-register"
    generated_at               = (Get-Date).ToString("yyyy-MM-ddTHH:mm:sszzz")
    pdf_dir_relative           = "src/test/resources/pdfs"
    target_questions_per_paper = 5
    quota_plan                 = [ordered]@{ FACT = 15; METHOD = 15; NUMERIC = 15; EVIDENCE_LOCATE = 15; SYNTHESIS = 10; UNANSWERABLE = 5 }
    generator_model            = "UNKNOWN (not called yet)"
    checker_model              = "UNKNOWN (not called yet)"
    llm_calls                  = 0
    tokens                     = [ordered]@{ prompt = "unknown"; completion = "unknown"; total = "unknown" }
    notes                      = @(
        "Registration/audit only: no LLM called, no input PDF or production data modified.",
        "document_id / knowledge_base_id remain null until mapped to ingested documents (no guessing)."
    )
}
[System.IO.File]::WriteAllText((Join-Path $outDir "run_config.json"), ($runConfig | ConvertTo-Json -Depth 8),
    $utf8NoBom)

Write-Output ("registered_papers=" + $papers.Count)
$sum = 0
foreach ($p in $papers) { $sum += $p.page_count }
Write-Output ("page_count_heuristic_total=" + $sum + " (method disclosed in manifest; not verified)")
foreach ($p in $papers) { "{0}  {1,9} B  pages~{2,3}" -f $p.paper_id, $p.bytes, $p.page_count }
