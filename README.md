# Wilma Dashboard

Kodin seinätaulu, joka näyttää lasten Wilma-tiedot reaaliajassa vanhalle Android-tabletille.

## Mikä tämä on?

Wilma Dashboard on Android-sovellus, joka muuttaa vanhan Android-tabletin perheesi digitaaliseksi ilmoitustauluksi. Sovellus hakee automaattisesti lasten lukujärjestykset, tulevat kokeet ja kotitehtävät Wilma-järjestelmästä ja näyttää ne selkeästi rinnakkaisissa sarakkeissa — yksi sarake per lapsi.

Sovellus on suunniteltu toimimaan kioskitilassa: se käynnistyy automaattisesti ja pysyy koko ajan näytöllä ilman ylimääräisiä nappuloita tai valikoita.

## Ominaisuudet

- Näyttää jokaisen lapsen tiedot omassa sarakkeessaan
- Tämän päivän ja huomisen lukujärjestys tuntiaikoineen
- Tulevat kokeet järjestettynä lähimmästä kaukaisimpaan
- Kotitehtävät
- Automaattinen päivitys 10 minuutin välein
- Näyttö pimenee automaattisesti klo 22–06, herää napauttamalla
- Sarakkeen saa laajennettua koko näytölle napauttamalla otsikkoa
- Yksittäistä koketta napauttamalla saa näkyviin koko selitystekstin
- Tuki suomen ja englannin kielelle
- Tumma ja vaalea teema

## Android-oikeudet

Sovellus pyytää seuraavat oikeudet:

| Oikeus | Tarkoitus |
|--------|-----------|
| `INTERNET` | Yhteys Wilma-palvelimeen tietojen hakemiseksi |
| `WAKE_LOCK` | Estää laitetta sammumasta kesken tiedonhaun |
| `RECEIVE_BOOT_COMPLETED` | Käynnistää sovelluksen automaattisesti laitteen käynnistyessä |

Sovellus ei pyydä pääsyä kameraan, sijaintiin, kontakteihin, tallennustilaan tai mihinkään muuhun arkaluonteiseen dataan.

## Vaatimukset

- Android 4.2.2 tai uudempi (yhteensopiva Android 14 asti, testattu Samsung SM-T110 -tabletilla)
- Wilma-tunnukset (huoltajan käyttäjätunnus ja salasana)
- Wilma-palvelimen osoite (esim. `https://koulu.inschool.fi`)

## Käyttöönotto

1. Lataa uusin APK-tiedosto [Releases](../../releases)-sivulta tai GitHub Actions -artefakteista
2. Salli tuntemattomien lähteiden asennus tabletin asetuksista
3. Asenna APK
4. Avaa sovellus ja syötä Wilma-palvelimen osoite sekä huoltajan tunnus ja salasana

**Automaattinen käynnistys virransyötön jälkeen:** Sovellus on ohjelmoitu käynnistymään automaattisesti, kun tabletti käynnistetään. Android 4.2.2 edellyttää kuitenkin, että sovellus on käynnistetty vähintään kerran manuaalisesti ennen kuin automaattinen käynnistys toimii — tämä on Androidin tietoturvarajoitus. Riittää siis, että sovellus avataan kerran asennuksen jälkeen ja kirjautuminen tehdään loppuun. Sen jälkeen sovellus käynnistyy aina automaattisesti tabletin käynnistyessä.

## Teknistä taustaa

Sovellus koostuu kahdesta osasta:

**Android-sovellus (Java):** WebView-pohjainen kuori, joka hoitaa Wilma-kirjautumisen ja tiedonhaun suoraan HTTP-pyyntöinä. Käyttää epävirallista mutta vakaata Wilma JSON API:a (`/!NUMERO/overview`-päätepistettä). Kirjautuminen tapahtuu HTML-lomakkeen kautta samoin kuin selaimessa.

**Käyttöliittymä (HTML/CSS/JS):** Yksittäinen HTML-tiedosto, joka on kirjoitettu ES5-yhteensopivaksi vanhan Android WebKit -version takia. Ei ulkoisia kirjastoja, ei Google-fontteja — pelkkä natiivi selain.

## Huomioita

Tämä projekti on rakennettu henkilökohtaiseen käyttöön. Wilma ei tarjoa virallista julkista API:a, joten sovellus käyttää samoja epävirallisia päätepisteitä kuin esimerkiksi [wilmai](https://github.com/samnuutinen/wilmai)-projekti. Toimivuus voi muuttua, jos Wilman palvelinpuoli päivittyy.

## Tietoturva

Tunnukset tallennetaan tabletin paikalliseen SharedPreferences-tallennustilaan. Tietoja ei lähetetä mihinkään kolmannelle osapuolelle. Kaikki liikenne kulkee suoraan laitteen ja koulun Wilma-palvelimen välillä.

## Lisenssi

MIT
