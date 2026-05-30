import http from 'http';
import { XMLParser } from 'fast-xml-parser';

export interface Arrival {
  line: string;
  route: string;
  destination: string;
  meters: number;
  minutes: number;
}

/**
 * Fetches real-time arrivals for a given stop code from the Bizkaibus SOAP Web Service.
 * Parses the escaped XML result and returns a list of normalized Arrival objects sorted by minutes.
 */
export function fetchSoapArrivals(stopCode: string): Promise<Arrival[]> {
  const soapEnvelope = `<?xml version="1.0" encoding="utf-8"?>
<soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
  <soap:Body>
    <GetPasoParada xmlns="http://tempuri.org/LANTIK_TQWS/TQ">
      <strParada>${stopCode}</strParada>
      <strLinea></strLinea>
    </GetPasoParada>
  </soap:Body>
</soap:Envelope>`;

  const options = {
    hostname: 'apli.bizkaia.net',
    port: 80,
    path: '/apps/danok/tqws/tq.asmx',
    method: 'POST',
    headers: {
      'Content-Type': 'text/xml; charset=utf-8',
      'SOAPAction': 'http://tempuri.org/LANTIK_TQWS/TQ/GetPasoParada',
      'Content-Length': Buffer.byteLength(soapEnvelope),
      'User-Agent': 'Mozilla/5.0'
    },
    timeout: 10000 // 10s timeout
  };

  return new Promise((resolve) => {
    const req = http.request(options, (res) => {
      if (res.statusCode !== 200) {
        console.error(`SOAP Request failed with status ${res.statusCode}`);
        resolve([]);
        return;
      }

      let data = '';
      res.on('data', (chunk) => { data += chunk; });
      res.on('end', () => {
        try {
          const startTag = '<GetPasoParadaResult>';
          const endTag = '</GetPasoParadaResult>';
          const startIdx = data.indexOf(startTag);
          const endIdx = data.indexOf(endTag);
          
          if (startIdx === -1 || endIdx === -1) {
            resolve([]);
            return;
          }

          const resultEscaped = data.substring(startIdx + startTag.length, endIdx);
          
          // Unescape XML entities
          const unescaped = resultEscaped
            .replace(/&lt;/g, '<')
            .replace(/&gt;/g, '>')
            .replace(/&amp;/g, '&')
            .replace(/&quot;/g, '"')
            .replace(/&apos;/g, "'");

          // Strip the "1®" status prefix if present
          const cleanXmlStart = unescaped.indexOf('<GetPasoParadaResult>');
          if (cleanXmlStart === -1) {
            resolve([]);
            return;
          }
          const xmlToParse = unescaped.substring(cleanXmlStart);

          const parser = new XMLParser({
            ignoreAttributes: false,
            parseTagValue: false,
            isArray: (name) => name === 'PasoParada'
          });

          const parsedObj = parser.parse(xmlToParse);
          const resultContainer = parsedObj['GetPasoParadaResult'];
          if (!resultContainer) {
            resolve([]);
            return;
          }

          const pasoParadas = resultContainer['PasoParada'] || [];
          const arrivals: Arrival[] = [];

          for (const item of pasoParadas) {
            const line = item['linea'];
            if (!line) continue;
            
            const route = item['ruta'] || '';

            const addArrival = (eNode: any) => {
              if (!eNode || !eNode['destino']) return;
              arrivals.push({
                line,
                route,
                destination: eNode['destino'],
                meters: parseInt(eNode['metros'] || '0', 10),
                minutes: parseInt(eNode['minutos'] || '0', 10)
              });
            };

            addArrival(item['e1']);
            addArrival(item['e2']);
          }

          // Sort arrivals in ascending order of minutes remaining
          arrivals.sort((a, b) => a.minutes - b.minutes);
          resolve(arrivals);
        } catch (e: any) {
          console.error('SOAP XML parse error:', e.message);
          resolve([]);
        }
      });
    });

    req.on('error', (e) => {
      console.error('SOAP Network connection error:', e.message);
      resolve([]);
    });

    req.write(soapEnvelope);
    req.end();
  });
}
