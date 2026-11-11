# XRechnung: specification and validator

Billery writes XRechnung 3.0 (UBL for the separate download, CII inside the ZUGFeRD PDF) and
checks every generated document against the official **KoSIT validator** in CI.

Nothing of the KoSIT bundle is kept in this repository. The tests fetch what they need from the
official releases, with fixed SHA-256 checksums:

```bash
scripts/fetch-kosit-validator.sh /tmp/kosit
cd backend && XRECHNUNG_VALIDATOR_DIR=/tmp/kosit \
  mvn test -Dtest='VatAndEInvoiceTest#generatedXRechnungsPassTheKositValidator,ZugferdTest'
```

| Part | Version | Source | License |
|------|---------|--------|---------|
| Validator (Prüftool) | 1.5.0 | <https://github.com/itplr-kosit/validator/releases/tag/v1.5.0> | Apache 2.0 |
| Validator configuration XRechnung | 3.0.2, 2025-07-10 | <https://github.com/itplr-kosit/validator-configuration-xrechnung/releases/tag/release-2025-07-10> | see the release |

The specification itself (XRechnung 3.0.2, the UBL syntax binding, the CIUS XRechnung CVD), the
Schematron rules, the visualization and the test suite are published by the Koordinierungsstelle
für IT-Standards (KoSIT) at <https://xeinkauf.de/xrechnung/>.
