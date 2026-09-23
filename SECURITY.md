# Security boundaries

This is a single-tenant interview demo intended for a trusted local/internal network. It has no authentication, tenant isolation, malware scanner, sandbox, or secret-management service. Do not expose the upload API to the public Internet.

- Chunk and complete-file SHA-256 checks validate transfer integrity. The default limit is 2 GiB per upload, but GB-scale throughput has not been load-tested.
- Files are stored beneath a configured media root. The filename is not used as a filesystem path. FFprobe and FFmpeg are invoked with fixed command arguments; untrusted media still reaches those parsers, so keep the runtime patched and isolated.
- Transcript text and model output are untrusted. The dashboard renders them with `textContent` rather than HTML insertion.
- The tool registry denies arbitrary shell commands, writes, undeclared tools, and remote `mcp:*` calls. There is no remote MCP transport.
- OpenAI-compatible mode sends transcript text to the configured model endpoint. Only enable it with a trusted provider and explicit data authorization.
- Redis and Kafka use plaintext connections in the local Compose stack. Configure network isolation and credentials before any non-local deployment.

Please report vulnerabilities privately to the repository owner; do not post credentials or private media in public issues.
