# Rewloy Kotlin

**Rewloy API'nin resmî Kotlin, Java ve Android kütüphanesi.**

> **Durum: önizleme (0.x); API kararlı, kütüphane arayüzü 1.0'a kadar değişebilir.**

[Rewloy](https://rewloy.com), işletmelerin dijital sadakat kartlarını
müşterinin telefonuna koyar. Kart türleri damga, puan, VIP, cashback, hediye
kartı, kupon ve indirimdir:
- iPhone'da Apple Cüzdan;
- Android'de Rewloy Cüzdan ve Google Cüzdan;
- her yerde web kartı.

Kasada QR okutulur; bakiye, ödül ve kampanyalar kartın kendisinde güncellenir.
Panelde yapılabilen her şey [Rewloy API v1](https://rewloy.com/gelistiriciler)
ile de yapılabilir (geliştirici belgeleri: **https://rewloy.com/gelistiriciler**).
Bu kütüphane onu **Android POS terminallerinden ve yeni
nesil yazar kasalardan** (çoğu Android çalıştırır), ayrıca her JVM'den (Java ve
Kotlin) kullanır. Bir Android uygulaması değil, kütüphanedir; Android SDK'sı
gerekmez.

- **Tam tipli.** API'nin her işlemi, `operationId` adıyla bir metottur. İstek
  gövdeleri, sorgular ve yanıtlar OpenAPI belgesinden
  ([`openapi.json`](https://app.rewloy.com/v1/openapi.json)) üretilen sınıflarla
  gelir. CI belgeyi her gün okur ve değişince yeniden üretir.
- **Eski kasalara uyar.** JVM 8 bayt kodu, Android 5.0 (API 21) ve üstü. Tek
  bağımlılığı Kotlin standart kütüphanesidir; HTTP için her JVM'de ve Android'de
  bulunan `HttpURLConnection` kullanılır (`java.net.http` Android'de yoktur).
- **Java'dan rahat.** Metotlar bloklar, istisnalar denetimsizdir, sayfalama ve
  akış `for` ile dolaşılır. Kotlin'de `suspend` ve `Flow` için ayrı, isteğe
  bağlı bir paket vardır.
- **Güvenli tekrar.** Geçici hatalarda ölçülü yeniden deneme; satışta, kasa
  işleminde ve kampanyada `Idempotency-Key`.
- **Ötesi:** sayfalama, canlı akış (SSE), webhook imzası doğrulama, iptal,
  kullanımdan kalkma bildirimleri, test modu.

## Kurulum

Paketler Rewloy'un kendi Maven deposundadır: **https://maven.rewloy.com**
(Maven Central'da değil; nedeni [docs/DECISIONS.md](docs/DECISIONS.md), 32).
Depo yalnız HTTPS ile sunulur; adresi `http://` ile yazmayın. Kütüphane JVM 8
için derlenir. Kotlin kullanıyorsanız derleyiciniz 2.0 ya da üstü olmalıdır;
yalnız Java kullanıyorsanız Kotlin derleyicisine gerek yoktur.

**Gradle (Kotlin DSL)**, `build.gradle.kts`. Android Studio projelerinde
`repositories` bloğu `settings.gradle.kts` içinde,
`dependencyResolutionManagement` altındadır. `exclusiveContent` ile `com.rewloy`
paketleri yalnız bu depodan alınır (başka bir depodaki aynı adlı bir paket
onların yerine geçemez) ve bu depoya başka bir şey sorulmaz.

```kotlin
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.rewloy.com") }
        filter { includeGroup("com.rewloy") }   // com.rewloy yalnız buradan, burada yalnız com.rewloy
    }
}

dependencies {
    implementation("com.rewloy:rewloy:0.2.4")
    // isteğe bağlı:
    implementation("com.rewloy:rewloy-okhttp:0.2.4")      // OkHttp taşıyıcısı
    implementation("com.rewloy:rewloy-coroutines:0.2.4")  // suspend ve Flow
}
```

**Gradle (Groovy)**, `build.gradle`:

```groovy
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven { url = 'https://maven.rewloy.com' } }
        filter { includeGroup 'com.rewloy' }
    }
}

dependencies {
    implementation 'com.rewloy:rewloy:0.2.4'
}
```

**Maven**, `pom.xml`:

```xml
<repositories>
  <repository>
    <id>rewloy</id>
    <url>https://maven.rewloy.com</url>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.rewloy</groupId>
    <artifactId>rewloy</artifactId>
    <version>0.2.4</version>
  </dependency>
</dependencies>
```

Her paketin yanında kaynak jar'ı (IDE, KDoc açıklamalarını buradan gösterir),
POM ve sağlama toplamları vardır.

**Kaynaktan derlemek** de mümkündür (Gradle için JDK 17 ya da üstü gerekir):

```sh
git clone https://github.com/Rewloy/rewloy-kotlin
cd rewloy-kotlin
./gradlew publishToMavenLocal
```

Sonra projenizde `mavenLocal()` açıkken aynı koordinatları kullanın (sürüm,
`build.gradle.kts`'teki sürümdür).

## Başlarken

```kotlin
import com.rewloy.Rewloy

val rewloy = Rewloy { apiKey(System.getenv("REWLOY_API_KEY")) }

val kart = rewloy.getPass("ABCD-EFGH-JKLM")
// "Şimdi ne yapılabilir?" için `actions[].ready` okunur; `rewardReady` yalnız damga ve puanda "ödül hazır"dır.
val odul = kart.actions.any { (it.action == "redeem-stamps" || it.action == "redeem-reward") && it.ready }
println("${kart.type} ${kart.balance} $odul")
```

Java:

```java
Rewloy rewloy = new Rewloy(RewloyOptions.builder().apiKey(System.getenv("REWLOY_API_KEY")).build());
GetPassData kart = rewloy.getPass("ABCD-EFGH-JKLM");
```

**Metotlar bloklar.** Bir çağrı yanıt gelene kadar bekler; Android'de ana
iş parçacığında çağırmayın (iş parçacığı, `Executor` ya da Kotlin için
`rewloy-coroutines`). İstemci iş parçacığı güvenlidir ve programınız boyunca
yaşamak üzere tasarlanmıştır: bir tane yapın, paylaşın.

Her işlem, adı `operationId` olan bir metottur
([API referansı](https://rewloy.com/gelistiriciler/api)). Argümanlar sırayla:
adresteki parametreler (`serial`, `id`…), varsa gövde, varsa sorgu, sonra
`RequestOptions`:

```kotlin
rewloy.passAction(serial, PassActionBody("earn-stamps", locationId), RequestOptions(idempotencyKey = "kasa3-z0187-fis$fisNo"))
rewloy.listCustomers(ListCustomersQuery(q = "ayşe", limit = 50))
```

- **Gövde ve sorgu sınıfları.** `PassActionBody`, `ListCustomersQuery`… Zorunlu
  alanlar kurucudadır; Kotlin'de gerisi adlandırılmış argümandır
  (`ListCustomersQuery(limit = 50)`), Java'da yalnız zorunlu alanları alan bir
  kurucu ve `setX` metotları vardır. Verilmeyen alan gönderilmez. Bir alanı
  `null` olarak göndermek gereken yerlerde (`correctHolderProfile`…)
  `OptionalField.of(değer)` ve `OptionalField.ofNull()` kullanılır.
- **Yanıtlar.** `data` döner: bir sınıf, sayfalı listede `Page<Öğe>` (`data` ve
  `meta`), gövdesiz yanıtta (`204`) `Unit`, dosyada (QR, harita, CSV, `.pkpass`)
  `RewloyFile`, OpenAPI belgesinde `JsonValue`.
- **Tarih ve kimlik** alanları `String`'dir (kimlikler UUID, tarihler ISO 8601
  metni): Android 8'in altında `java.time` yoktur.
- **Biçimi belli olmayan** alanlar `JsonValue`'dur (`asString()`, `asLong()`,
  `asObject()`…); kütüphanenin kendi küçük JSON okuyucusu vardır.
- **API yeni alan eklerse** yanıt sınıfındaki `additionalProperties` onu tutar;
  istekte `setAdditionalProperty(ad, değer)` yeni bir alanı kütüphane
  güncellenmeden gönderir.
- **Yanıt belgelenen tipe uymazsa** (zorunlu bir alan yok ya da başka tipte)
  `INVALID_RESPONSE` kodlu `RewloyException` atılır; `body` ham yanıtı, `detail`
  yolunu söyler (`$.data.serial`). İşlem sunucuda yapılmış olabilir:
  `Idempotency-Key` ile tekrar aynı sonucu verir.

### Kimlik

| İstemci | Ne için |
|---|---|
| `Rewloy { apiKey("rwk_…") }` | API anahtarı: kasa, e-ticaret, kendi sisteminiz |
| `Rewloy { staffSession("rws_…"); merchant(isletmeId) }` | ekip oturumu: bir kişinin işletme uygulaması |
| `Rewloy { holderSession("rwh_…") }` | kart sahibi oturumu: Rewloy Cüzdan gibi müşteri uygulamaları |
| `Rewloy()` | kimlik istemeyen uç noktalar: giriş, katılım, kod |

`merchant`, ekip oturumu birden fazla işletmede koltuk taşıyorsa hangi işletme
için çalıştığını söyler (`Rewloy-Merchant`); her çağrıda
`RequestOptions(merchant = …)` ile değiştirilebilir. Oturumlar kimliksiz bir
istemciyle açılır:

```kotlin
val oturum = Rewloy().login(LoginBody(email, password))
val ekip = Rewloy { staffSession(oturum.token); merchant(isletmeId) }
if (oturum.mfaRequired) ekip.proveMfa(ProveMfaBody("123456"))
```

Bir işlem istemcinin kimlik türünü kabul etmiyor ama kimliksiz de çalışıyorsa
(örneğin `login`), istemci onu kimliksiz çağırır. Yanlış öneki olan ya da iki
kimlik verilmiş bir istemci kurulurken `IllegalArgumentException` atar; mesajı
anahtarın kendisini içermez.

Diğer seçenekler (`Rewloy { … }` ya da `RewloyOptions.builder()`):
- `baseUrl` (varsayılan `https://app.rewloy.com`; sonuna `/v1` eklemeniz ya da eklememeniz fark etmez: `https://app.rewloy.com/v1` de olur, kütüphane `/v1`i kendisi ekler);
- `timeoutMs` (60000): bir denemenin tamamı için;
- `maxRetries` (2);
- `userAgent`: gönderilen `User-Agent`a eklenir, örneğin `"KasaPOS/4.2"`;
- `transport`: HTTP katmanı (aşağıda);
- `deprecationListener` ve `sleeper`.

### Başka bir adres (staging)

API'nin başka bir kopyasına (kendi staging ortamınız ya da bir vekil sunucu)
`baseUrl` ile bağlanılır:

```kotlin
val rewloy = Rewloy {
    apiKey(System.getenv("REWLOY_API_KEY"))
    baseUrl("https://rewloy-staging.ornek.com")   // sonuna /v1 yazsanız da olur
}
```

Java'da `RewloyOptions.builder().apiKey(…).baseUrl("https://…").build()`.
Gerçek müşterilere dokunmadan denemek için adres değiştirmeniz gerekmez:
[test modu](#test-modu) aynı adreste, ayrı bir test ortamıyla çalışır.

## Kart vermek ve kasada işlem

```kotlin
val kart = rewloy.issuePass(IssuePassBody(programId = programId, email = "ayse@ornek.com", firstName = "Ayşe", kvkkConsent = true))
println(kart.serial + " " + kart.cardUrl)

val sonuc = rewloy.passAction(
    kart.serial,
    PassActionBody("earn-stamps", locationId).apply { count = 1 },
    RequestOptions(idempotencyKey = "kasa3-z0187-fis$fisNo"),   // aşağıya bakın
)
if (sonuc.duplicate) println("Bu işlem zaten yazılmış")
```

### Satış: `recordSale`

Kasa ya da kendi yazılımınız için en kolay yol `recordSale`dir: "bu satış
oldu, sen yaz". Ödenen toplamı (kartın para biriminde, kuruş) gönderirsiniz;
ne yazılacağına kartın türü ve programın kendi kuralı karar verir. Kartın
türünü bilmeniz gerekmez.

```kotlin
val kart = rewloy.getPass(seri)
// Kartın türüne özgü alanlar; `balance` yerine bunları okuyun.
kart.stamps?.let { println("${it.count} / ${it.max} damga") }
kart.points?.let { println("$it puan") }
kart.money?.let { println("${it.amountMinor / 100.0} ${it.currency}") }
println("${kart.programName} ${kart.customer?.name ?: ""}")   // customer: yalnız customers.read yetkisiyle

// Fiş numarası anahtar olamaz: kasa + Z no + fiş no, ya da satışla saklanan bir UUID.
val anahtar = "kasa3-z0187-fis$fisNo"
val satis = rewloy.recordSale(
    seri,
    RecordSaleBody(
        amountMinor = 4550,            // 45,50: kartın para biriminde (kart.currency), kuruş
        locationId = locationId,
        reference = "fis-$fisNo",      // fiş numarası buraya yazılır
        currency = kart.currency,      // isteğe bağlı güvence: uyuşmazsa 422 CURRENCY_MISMATCH
    ),
    RequestOptions(idempotencyKey = anahtar),
)
if (satis.applied == "none") println("Yazılan bir şey yok: ${satis.reason}")
else println("${satis.credited} ${satis.applied} yazıldı, bakiye ${satis.balance}")
// Fişi çizmek için ayrıca okumanız gerekmez: yazımdan sonraki kart `satis.card`'dadır (yetki yoksa null).
if (satis.card?.actions?.any { (it.action == "redeem-stamps" || it.action == "redeem-reward") && it.ready } == true) println("Ödül hazır")
```

`actions[].ready`, kartın kendi durumuna göre işlemin şimdi yapılıp
yapılamayacağıdır (damga ödülü hazır mı, puan bir ödüle yetiyor mu, bakiye var
mı, kupon kullanılmamış mı, VIP ziyareti bu pencerede sayılmış mı). `rewardReady`
aynen kalır ama türe göre anlam değiştirir: damga ve puanda "ödül hazır";
cashback ve hediye kartında bakiye sıfırdan büyükse; **VIP'te her zaman
`true`**. Kasa ekranında "Ödül hazır" yazısını yalnız damga ve puanda gösterin.

`GET /v1/passes/{serial}` ayrıca `actions` (kartın aldığı kasa işlemleri ve
şimdi yapılıp yapılamayacakları) ve `sale` (bir satışın bu kartta ne
yazacağı) alanlarını verir.

**İade.** `reverseSale` bir satışın karta yazdığını geri alır; satışı
yazarken gönderdiğiniz anahtarla (`saleKey`) ya da `reference`la bulur:

```kotlin
val geri = rewloy.reverseSale(seri, ReverseSaleBody(saleKey = anahtar, locationId = locationId))
println("${geri.reversed} ${geri.applied} geri alındı, bakiye ${geri.balance}")
```

Bir satış bir kez geri alınır (tekrar `duplicate: true` döner). Kazanılan
kullanılmışsa (ödüle ya da harcamaya gitmişse) `409 SALE_ALREADY_SPENT` gelir ve
hiçbir şey yazılmaz.

**Çevrimdışı kasa kuyruğu: `occurredAt`.** Bağlantı koptuğunda satışı sonra
yazıyorsanız `occurredAt` ile satışın gerçekten olduğu anı (ISO 8601 metni, saat
dilimiyle) gönderin; kartın geçmişinde o anla görünür. Gelecekte olamaz (2
dakikalık saat farkı kabul edilir). `idempotencyKey` kuyruktaki kayıtla birlikte
saklanır, tekrar gönderilince satış ikinci kez yazılmaz.

```kotlin
rewloy.recordSale(
    seri,
    RecordSaleBody(amountMinor = 4550, locationId = locationId, reference = "fis-$fisNo", occurredAt = "2026-10-05T14:32:10+03:00"),
    RequestOptions(idempotencyKey = anahtar),
)
```

**Kasa işlemini iptal etmek: `reverseAction`.** `passAction` ile yapılan bir
harcama, ödül ya da kullanım yanlışlıkla yapıldıysa (`spend`, `spend-points`,
`redeem-stamps`, `redeem-reward`, `use`) `reverseAction` tamamını geri verir.
İşlemi, yaparken gönderdiğiniz `Idempotency-Key` (`actionKey`) ya da işlemin
`reference` değeriyle bulur (`PassActionBody` artık isteğe bağlı bir `reference`
alır). `reverseAction` bir `Idempotency-Key` **istemez**: bir işlem bir kez geri
alınır, tekrar `duplicate: true` döner.

```kotlin
rewloy.passAction(
    seri,
    PassActionBody("spend", locationId).apply { amountMinor = 2500 },
    RequestOptions(idempotencyKey = "kasa3-z0187-iptal$fisNo"),
)
val iptal = rewloy.reverseAction(seri, ReverseActionBody(actionKey = "kasa3-z0187-iptal$fisNo", locationId = locationId))   // ya da reference = "fis-$fisNo"
println("${iptal.undone} ${iptal.restored} geri verildi, bakiye ${iptal.balance}")
```

`passAction`ın yanıtı kart türüne göre iki biçimdedir ve `PassActionData`
mühürlü (`sealed`) bir sınıftır: bakiyeli kartlarda `PassActionDataOption1`
(`balance`: damga, puan, VIP, cashback, hediye kartı), kupon ve indirim kartında
`PassActionDataOption2` (`status`, `uses`, `usesLeft`). İkisinde de olan
`duplicate` doğrudan okunur; gerisi için `when` kullanın (Java'da `instanceof`):

```kotlin
when (val sonuc = rewloy.passAction(seri, govde, RequestOptions(idempotencyKey = anahtar))) {
    is PassActionDataOption1 -> println("bakiye ${sonuc.balance}")
    is PassActionDataOption2 -> println("${sonuc.uses} kullanım, ${sonuc.usesLeft} kaldı")
}
```

Kazanımlar (`earn-stamps`, `earn-points`, `visit`) `reverseAction`la değil
`reverseSale`la geri alınır.

**Yazımın yanıtında kartın durumu: `card`.** `recordSale`, `passAction`,
`reverseSale` ve `reverseAction` yanıtları `card` taşır: yazımdan sonraki kart,
`getPass`'in `customer` hariç aynı alanlarıyla (`programName`, `currency`,
`stamps`/`points`/`money`, `actions`…). Yazımla aynı işlemde okunur, yanıtın
`balance`'ıyla aynı anı söyler. **Tekrarda** (`duplicate == true`) kartın
**şimdiki** durumudur. Kimliğin kartın programında `passes.read` yetkisi yoksa
(yalnız kasa yetkisi olan bir eklenti anahtarı) `card` `null`dır. `recordSale`
ve `passAction` yanıtındaki `reversed == true`, bu anahtarla yazılan işlemin
sonradan geri alındığını söyler (yalnız bir tekrarda olabilir; `credited` ilk
isteğin yazdığıdır, kart onu artık taşımaz): fişi yeniden yazmak için yeni bir
anahtar gönderin. `passAction`ın mühürlü sınıfında `reversed` doğrudan okunur;
`card` her şeklin kendisindedir (`PassActionDataOption1.card`,
`PassActionDataOption2.card`). **0.2.4 Rewloy API 1.2.0'ı ve sonrasını okur:**
`card` ve `reversed` zorunlu alanlardır, 1.1.x'e karşı bu çağrılar
`INVALID_RESPONSE` atar.

**Kartın işlemleri: `listPassOperations`.** Kartın defterindeki işlemler,
yeniden eskiye, sayfalı (`listPassOperationsAll(seri)`): bir kasa ekranındaki
"son işlemler" listesi ve her birinin İade düğmesi için; kasanın kendi anahtar
günlüğünü tutması gerekmez. Her işlemde `undoWith` hangi uç noktanın geri
aldığını (`"sale/reverse"` ya da `"actions/reverse"`), `reversible` bu kimliğin
şimdi geri alıp alamayacağını söyler; bu kimliğin kendi işlemlerinde `saleKey`
ya da `actionKey` de gelir.

```kotlin
for (islem in rewloy.listPassOperationsAll(seri)) {
    if (!islem.reversible) continue
    if (islem.undoWith == "sale/reverse") rewloy.reverseSale(seri, ReverseSaleBody(saleKey = islem.saleKey))
    else rewloy.reverseAction(seri, ReverseActionBody(actionKey = islem.actionKey))
}
```

**`occurredAt` reddedilirse** `400 VALIDATION` gelir ve `details` içindeki ilk
kaydın `reason`'ı nedeni söyler: `in_future`, `too_old` (72 saatten eski),
`before_issue` (kart o anda yoktu: `occurredAt` olmadan yeniden gönderin),
`invalid`. Tanımadığınız bir `reason`'ı `invalid` gibi ele alın.

### `Idempotency-Key`

`recordSale`, `passAction`, `sendCampaign` ve `refundShopRedemption` bir
`Idempotency-Key` **ister**: API'nin tanımında (OpenAPI) bu başlık bu işlemlerde
zorunludur, bu yüzden `RequestOptions.idempotencyKey` bu metotlarda zorunludur.
Verilmezse kütüphane istek göndermeden `IllegalArgumentException` fırlatır;
**sizin yerinize anahtar üretmez**. Üretilmiş rastgele bir anahtar yalnızca tek
çağrının yeniden denemelerini korurdu: uygulama çöküp yeniden başlarsa yeni bir
anahtar çıkar ve satış ikinci kez yazılabilirdi. Anahtarı kendiniz üretip
satışla birlikte saklayın. Anahtar 8–64 karakterlik görünür ASCII olmalıdır
(0x21–0x7E: harf, rakam ve noktalama; boşluk, Türkçe harf ya da `fiş` gibi
ASCII dışı karakter olmaz); aksi halde kütüphane yine istek göndermeden
`IllegalArgumentException` fırlatır. Başlığın isteğe bağlı olduğu işlemlerde
(örneğin `issuePass`) anahtar verilmezse kütüphane bir UUID üretir ve aynı
çağrının her denemesinde aynısını gönderir.
- **Anahtar bir kimlik için kalıcı olarak tekildir** (8–64 karakter; defterden
  hiç silinmez). Aynı anahtarla aynı isteğin tekrarı ikinci kez yazmaz ve
  ilk sonucu `duplicate: true` ile döndürür. Aynı anahtar başka bir gövdeyle
  `422 IDEMPOTENCY_KEY_REUSED` alır.
- **Fiş numarası tek başına anahtar olamaz:** yazarkasa fiş numaraları Z
  raporundan sonra yeniden başlar. Kasa + Z no + fiş no birleşimi
  (`kasa3-z0187-fis0042`) ya da satışla birlikte saklanıp tekrarda yeniden
  gönderilen bir UUID kullanın.
- **Fiş numarası `reference` alanına** yazılır; müşterinin geçmişinde ve işlem
  dökümünde görünür.

## Sayfalama

```kotlin
for (musteri in rewloy.listCustomersAll(ListCustomersQuery(consent = "yes", limit = 200))) {
    println("${musteri.displayName} ${musteri.email}")
}
```

`…All` sayfalı her listeyi (`page`/`limit` ve `meta`) öğe öğe dolaşır ve son
sayfada durur; hiçbir şey dolaşma başlayana kadar istenmez, ve her `iterator()`
baştan başlar. Tek bir sayfa için metodun kendisi yeter:
`val sayfa = rewloy.listCustomers(ListCustomersQuery(page = 2)); sayfa.data; sayfa.meta`.

## Canlı akış

```kotlin
thread {
    rewloy.liveFeed().use { akis ->
        for (olay in akis) {
            if (olay.event == "event") println(olay.json())
        }
    }
}
```

`liveFeed` (işletmenin tezgâh akışı) ve `holderCardEvents` (kart sahibinin
kartındaki değişiklik) sunucu olayları (`text/event-stream`) yayınlar.
Dolaşma **bloklar**: bir iş parçacığında çalıştırın. Java'da
`try (EventStream akis = rewloy.liveFeed()) { for (ServerSentEvent olay : akis) … }`.

- **Yeniden bağlanma.** Bağlantı koparsa akış kendiliğinden yeniden bağlanır:
  sunucunun `retry:` süresi kadar bekler, bir olay `id` taşıdıysa
  `Last-Event-ID` gönderir. `RequestOptions(reconnect = false)` bunu kapatır.
- **Sessiz bağlantı.** API 25 saniyede bir `: hb` gönderir; 60 saniye hiç veri
  gelmezse bağlantı kopmuş sayılır (`idleTimeoutMs`).
- **Durdurmak:** `akis.close()` (başka bir iş parçacığından da), `CancelToken`
  ya da döngüden çıkıp `use` bloğunu bitirmek; akış sessizce biter.
- **Bitiren hatalar.** Yeniden bağlanmanın düzeltemeyeceği bir hata (`401`,
  `403`, `404`) dolaşmayı `RewloyException` ile bitirir.

## İptal

```kotlin
val iptal = CancelToken()
thread { rewloy.passAction(serial, govde, RequestOptions(idempotencyKey = "kasa3-z0187-fis42", cancel = iptal)) }
// ekran kapanırken:
iptal.cancel()
```

İptal, bekleyen isteğin bağlantısını kapatır, bekleyen bir yeniden denemeyi
keser ve çağrı `java.util.concurrent.CancellationException` atar. Bir istemcinin
bütün çağrılarını bir jetona bağlamak için `rewloy.withCancel(jeton)`.

## Webhook doğrulama

Rewloy her teslimi imzalar:

```
Rewloy-Signature: t=<unix saniye>,v1=<hex HMAC-SHA256(sır, "<t>.<ham gövde>")>
```

`Webhook.verify` imzayı **ham gövdeyle** ve webhook oluşturulurken bir kez
gösterilen sırla (`whsec_…`) doğrular:
- karşılaştırmayı sabit sürede yapar;
- `t` şimdiden 300 saniyeden (`toleranceSeconds`) uzaksa reddeder;
- gövdeyi ayrıştırılmış olarak döndürür (`WebhookEvent`).

Webhook'u panelden ya da API'den ekleyebilirsiniz. `webhooks.manage` yetkili
bir API anahtarı `createWebhook`, `listWebhooks`, `getWebhook`,
`setWebhookStatus`, `testWebhook` ve `listWebhookDeliveries`yi çağırabilir;
`webhookEvents` abone olunabilecek olayları söyler. Sır (`secret`) yalnız
`createWebhook` yanıtında gelir, saklayın:

```kotlin
val yeni = rewloy.createWebhook(CreateWebhookBody("https://ornek.com/rewloy/webhook", listOf("pass.activity", "pass.voided")))
val sir = yeni.secret
rewloy.testWebhook(yeni.webhook.id)   // webhook.test olayı gönderir
```

Adres herkese açık bir `https` adresi olmalıdır (test ortamında da);
yerelde bir tünel kullanın.

**Sırrı yenilemek.** Kaybolan ya da sızan bir sır için `rotateWebhookSecret`
webhook'a yeni bir sır verir (yeni `secret` yalnız o yanıtta döner); webhook'u
silip yeniden eklemek gerekmez. Eski sır 24 saat daha yeninin yanında imzalar:
o sürede `Rewloy-Signature` iki `v1` taşır ve teslimler
`Rewloy-Signature-Rotating: 1` başlığıyla gelir. `Webhook.verify` her `v1`'i ve
verilen birden çok sırrı dener; yenilemeden önce alıcınızı `listOf(yeni, eski)`
ile güncelleyin. `deleteWebhook` webhook'u teslim geçmişiyle birlikte kalıcı
siler (`204`).

```kotlin
val yeni = rewloy.rotateWebhookSecret(webhookId).secret
// yeni sırrı alıcınıza ekleyin, 24 saat sonra eskisini bırakın
val olay = Webhook.verify(hamGovde, imzaBasligi, listOf(yeni, eskiSir))
```

Tutmazsa `WebhookSignatureException` atar (`reason`: `MISSING`, `MALFORMED`,
`EXPIRED`, `MISMATCH`, `PAYLOAD`): 400 ile yanıtlayın ve hiçbir işlem yapmayın.
Gövde mutlaka ham olmalıdır: JSON olarak ayrıştırılıp yeniden yazılan bir gövde
imzayı tutturmaz. Birden çok sır verilebilir (bir webhook'tan ötekine
geçerken).

```kotlin
// Ktor
post("/rewloy/webhook") {
    val ham = call.receive<ByteArray>()
    val olay = try {
        Webhook.verify(ham, call.request.header("Rewloy-Signature"), System.getenv("REWLOY_WEBHOOK_SECRET"))
    } catch (e: WebhookSignatureException) {
        return@post call.respond(HttpStatusCode.BadRequest)
    }
    // Rewloy-Delivery bir teslimin her denemesinde aynıdır: işlediyseniz atlayın.
    olay.passData?.let { println("${it.card} ${it.kind} ${it.delta}") }
    call.respond(HttpStatusCode.OK)
}
```

```java
// Servlet
byte[] raw = request.getInputStream().readAllBytes();   // Java 9+; ya da kendi okuyucunuz
try {
    WebhookEvent event = Webhook.verify(raw, request.getHeader("Rewloy-Signature"), secret);
    if ("pass.activity".equals(event.getType())) { … }
    response.setStatus(200);
} catch (WebhookSignatureException e) {
    response.setStatus(400);
}
```

Başlıklar:
- `Rewloy-Event`: olay türü (`pass.issued`, `pass.activity`, `pass.voided`,
  `webhook.test`); gövdedeki `type` ile aynı.
- `Rewloy-Delivery`: teslimin kimliği. Teslim "en az bir kez"dir: çift gelen
  teslimi bununla ayıklayın.

Gövde kişinin iletişim bilgisini taşımaz; kişiyi `customer_id` ile API'den
okuyun. 2xx dışı bir yanıt yaklaşık 45 saat boyunca 8 kez yeniden denenir ve
her deneme yeni bir `t` ile imzalanır. Kendi işleyicinizi test etmek için
`Webhook.sign(gövde, sır)` aynı başlığı üretir.

**Webhook'un durumu.** Webhook nesnesinde (`listWebhooks`, `getWebhook`,
`setWebhookStatus`, `createWebhook` ve `rotateWebhookSecret`'ın webhook'u)
iki tarih alanı hep vardır, ikisi de boş olabilir (`String?`, bir tarih):
- `pausedUntil`: alıcınız art arda iki kez `5xx`, `429` verdi ya da yanıt vermedi;
  açık webhook'un teslimleri bu ana kadar bekler, sonra kendiliğinden yeniden
  denenir (60 saniye). Bekletilmiyorsa ya da webhook kapalıysa boştur.
- `resumableUntil`: webhook'u **kurallar** kapattı ve bekleyen teslimleri
  saklanıyor (kapanıştan 24 saat sonrasına kadar). Bu andan önce
  `setWebhookStatus(id, SetWebhookStatusBody(active = true))` ile
  açarsanız kaldığı yerden devam eder: saklananlar hemen gider, kapalıyken olan
  olaylar da gelir. Açıksa, bir kişi ya da anahtar kapattıysa ya da süre geçtiyse boştur.

```kotlin
for (w in rewloy.listWebhooks()) {
  w.pausedUntil?.let { println("${w.url}: $it anına kadar bekletiliyor") }
  w.resumableUntil?.let { println("${w.url}: $it öncesinde açın, kaldığı yerden sürer") }
}
```

## Hatalar ve yeniden deneme

```kotlin
try {
    rewloy.passAction(serial, govde, RequestOptions(idempotencyKey = "kasa3-z0187-fis$fisNo"))
} catch (e: RateLimitException) {
    println("${e.retryAfterSeconds} saniye sonra yeniden deneyin")
} catch (e: RewloyException) {
    if (e.code == ErrorCode.INSUFFICIENT_BALANCE) println(e.detail) else throw e
}
```

`RewloyException` denetimsizdir ve şunları taşır:
- `status`: HTTP durumu;
- `code`: API'nin sabit kodu ([hata kodları](https://rewloy.com/gelistiriciler/hatalar),
  `ErrorCode` sabitleri); kodunuz buna göre davranmalı;
- `title`: kodun katalogdaki başlığı;
- `detail`: API'nin açıklaması (Türkçe, değişebilir);
- `details`: varsa ayrıntı (`JsonValue`); doğrulama hatasında
  `[{ field, rule, message }]`;
- `requestId`: `x-request-id`; destek talebinde bunu verin;
- `body`, `headers` ve `operation`.

Alt sınıflar:
- `RateLimitException`: `429`; `retryAfterSeconds`. Her istisna (bu dahil)
  yanıtın `RateLimit-*` başlıklarını `rateLimit` olarak verir
  (`limit`, `remaining`, `resetSeconds`; başlık yoksa `null`);
- `RewloyConnectionException`: yanıt gelmedi (`status` 0, `code`
  `CONNECTION_ERROR`);
- `RewloyTimeoutException`: zaman aşımı (`TIMEOUT`).

Rewloy'un olmayan bir hata gövdesi (örneğin bir vekil sunucunun 502 sayfası)
`HTTP_502` gibi bir kodla gelir.

**Yeniden deneme.** Şunlar en çok `maxRetries` kez (varsayılan 2) yeniden
denenir: bağlantı hatası, zaman aşımı, `429`, `502`, `503`, `504` ve
Cloudflare'in `520`–`524` hataları.
- **Bekleme:** üstel ve rastgele (0,5 sn, 1 sn, 2 sn… en çok 8 sn); yanıt
  `Retry-After` taşıyorsa o kadar. `Retry-After` 60 saniyeden uzunsa
  beklenmez, hata size gelir.
- **Yalnız tekrarı güvenli istekler:** `GET`, `PUT`, `DELETE` ve
  `Idempotency-Key` taşıyan `POST`. İlk istek hâlâ işlenirken gelen
  `409 IDEMPOTENCY_IN_PROGRESS` de beklenip yeniden denenir. Diğer `POST` ve
  `PATCH` istekleri hiç tekrar edilmez.
- **Süre:** her deneme `timeoutMs` (varsayılan 60 sn) içinde bitmelidir.

## Kullanımdan kalkma

Kalkacak bir uç nokta en az 180 gün önceden duyurulur. O süre boyunca her
yanıt `Deprecation`, `Sunset` ve `Link` başlıklarını taşır.

- **Bildirim.** Kütüphane her işlem için bir kez `DeprecationListener`ı çağırır
  (`Rewloy { deprecationListener { n -> log.warn(n.message) } }`). Dinleyici
  yoksa bildirim `com.rewloy` günlükçüsüne (`java.util.logging`; Android'de
  Logcat'e gider) işlem başına bir kez yazılır.
- **Tipler.** O metot ve alan `@Deprecated` olarak işaretlenir; derleyiciniz
  uyarır.

## Yanıtın tamamı ve test modu

```kotlin
val yanit = rewloy.sendCampaignWithResponse(SendCampaignBody("Bu hafta kahveler 2 damga!"), RequestOptions(idempotencyKey = "kampanya-2026-10-03"))
yanit.statusCode  // 201
yanit.replayed    // true: aynı anahtarın ilk yanıtı yeniden döndü (Idempotent-Replayed)
yanit.requestId   // x-request-id
yanit.rateLimit   // RateLimit-* başlıkları: limit, remaining, resetSeconds (yoksa null)
yanit.mode        // Rewloy-Mode
yanit.isTestMode  // mode == "test"
yanit.data        // kampanya
```

Sonu `WithResponse` olan her metot yanıtın tamamını döndürür: `data`, sayfalı
listede `meta`, `statusCode`, `headers`, `requestId`, `rateLimit`, `mode` ve
`replayed`.

`mode`, yanıtın `Rewloy-Mode` başlığıdır: `live` ya da `test`. Başlık yoksa
`null`. Canlı akışta aynı bilgi `akis.mode`dadır.

## Test modu

Gerçek müşterilere dokunmadan denemek için işletmenizin bir **test ortamı**
vardır: ona bağlı ayrı bir işletme (adı "· Test" ile biter); kendi
programları, müşterileri, kartları, anahtarları ve webhook'ları. Panel →
Geliştirici → "Test ortamını aç" ya da `POST /v1/test/environment`. Orada
oluşturulan anahtar `rwk_test_` ile başlar ve aynı adreste, aynı yollarla
çalışır:

```kotlin
val rewloy = Rewloy { apiKey(System.getenv("REWLOY_TEST_KEY")) }   // rwk_test_…
println(rewloy.getPassWithResponse(seri).isTestMode)               // true
```

- Test ortamı hiçbir şey göndermez (e-posta, bildirim, SMS); kartlar
  cüzdanlara eklenmez. Gönderilmeyenler `GET /v1/test/messages` ile okunur.
- Webhook'lar teslim edilir ve `Rewloy-Test: 1` başlığıyla `"test": true`
  taşır.
- Gerçek müşteri verisini test ortamına girmeyin.
- `resetTestEnvironment` (1.2.0'dan beri) müşterileri, kartları, kodları ve
  kayıtları siler; ortamın kimliği, programları, şubeleri, anahtarları ve
  webhook'ları kalır, entegrasyonunuz aynı anahtarla sürer. Bir anahtar
  sızdıysa `ResetTestEnvironmentBody(revokeKeys = true)` anahtarları da geçersiz
  kılar ve webhook'ları kapatır. Yanıt `deleted` ve `kept` sayılarını verir;
  `closed` artık hep `null`dır.
- POS için anahtar: `createApiKey(CreateApiKeyBodyPos(kind = "pos", locationId = subeId, password = sifre, register = "Kasa 1"))`
  hazır Kasa rolüyle yalnız o şubede çalışan bir anahtar oluşturur; yanıttaki
  `baseUrl` POS'a yazılacak adrestir. Sıradan anahtar `CreateApiKeyBody` ile
  oluşturulur; `createApiKey` ikisini de alır.
- `listAllBatches` (`listAllBatchesAll`) işletmenin bütün hediye kartı, kupon ve
  indirim kodlarını sayfalar (`status` süzgeci: `open`, `full`, `expired`,
  `closed` ya da `archived`; satırın `state`'i de bunlardan biri: `archived`
  kodun programı arşivde demektir, bağlantısı kart vermez). Arşivdeki bir
  programa kod oluşturmak `409 PROGRAM_ARCHIVED` (`ErrorCode.PROGRAM_ARCHIVED`)
  verir.
- `sendBatchLink` kodun bağlantısını yalnız kod kart verirken e-postayla
  gönderir: durdurulmuş kod `410 BATCH_CLOSED`, süresi dolmuş `410 BATCH_EXPIRED`,
  kartları bitmiş `410 BATCH_FULL`, programı arşivde olan `409 PROGRAM_ARCHIVED`
  verir ve e-posta gitmez (1.2.0'dan önce son üçünde de giderdi). Kodları
  `ErrorCode` sabitleri (`ErrorCode.BATCH_FULL`…) içindedir.

Ayrıntı: https://rewloy.com/gelistiriciler#test-ortamı

İşlem tablosu da dışa açıktır: `RewloyOperations.passAction` →
`OperationInfo` (`method`, `path`, `credentials`, `idempotency`, `isPaged`,
`isStream`, `deprecation`…).

## HTTP katmanı

Varsayılan taşıyıcı `HttpURLConnection`'dır: her JVM'de ve her Android
sürümünde vardır, ek bağımlılık gerektirmez. Yönlendirmeleri izlemez.

- **`PATCH`.** Android'in `HttpURLConnection`'ı `PATCH` gönderir. Masaüstü JDK'sı
  göndermez: Java 11'e kadar kütüphane bunu aşar; Java 12 ve üstünde `PATCH`
  (API'nin 260 işleminden 12'si) `UnsupportedOperationException` atar ve
  `rewloy-okhttp`'a yönlendirir. Sunucu tarafında Java 12+ kullanıyorsanız
  OkHttp taşıyıcısını kullanın.
- **OkHttp.** Uygulamanızda zaten bir `OkHttpClient` (kendi havuzu, vekil ve
  sertifika ayarları, interceptor'ları) varsa:
  `Rewloy { apiKey(…); transport(OkHttpTransport(okHttpClient)) }`
  (`com.rewloy:rewloy-okhttp`).
- **Kendi taşıyıcınız.** `Transport` tek metotlu bir arayüzdür (`execute`);
  test ikizi, vekil ya da izleme için yazın.

## Kotlin: coroutines

`com.rewloy:rewloy-coroutines`:

```kotlin
val kart = rewloy.suspending { getPass("ABCD-EFGH-JKLM") }          // Dispatchers.IO'da çalışır
rewloy.suspending { liveFeed() }.asFlow().collect { println(it.json()) }
```

`suspending` bloğu `Dispatchers.IO`'da çalıştırır; coroutine iptal edilince
uçuştaki istek de iptal edilir. Yüzlerce metodun bir de `suspend` ikizini
üretmek yerine tek bir genel sarmalayıcı verilir: blok içinde birkaç çağrı
yapabilir, sayfalı bir listeyi dolaşabilirsiniz.

## Geliştirme

```sh
./gradlew generate                                  # canlı belgeden: openapi/openapi.json ve rewloy/src/main/kotlin/com/rewloy/generated/
./gradlew generate -Pfile=openapi/openapi.json      # kayıtlı belgeden
./gradlew check                                     # bütün testler, Android API 21 denetimi
./gradlew test -PtestJava=8                         # testleri Java 8 çalışma zamanında koşar
```

- `generated/` elle düzenlenmez; üreteç `generator/` altındadır (Kotlin; kütüphane
  ile aynı JSON okuyucuyu kullanır).
- Testler ağa çıkmaz: yerel bir sahte HTTP sunucusuyla (`com.sun.net.httpserver`)
  çalışır.
- CI her gün canlı belgeyi okur ve bir değişiklik varsa bir pull request açar.
- Kararlar: [docs/DECISIONS.md](docs/DECISIONS.md).

### Yayımlamak

Bir sürüm `v*` etiketiyle yayımlanır. `.github/workflows/release.yml` etiketin
`build.gradle.kts`'teki sürümle aynı olduğunu denetler, CI'ın testlerini koşar
ve sürümü `Rewloy/maven` deposuna (https://maven.rewloy.com) ekler; yalnız yeni
dosyaları gönderir. Kurulumu ve kuralları dosyanın başında yazılıdır.

1. Sürümü üç yerde yükseltin: `build.gradle.kts`, `RewloyVersion.CURRENT` ve
   CHANGELOG.md (bir test üçünün aynı olduğunu denetler).
2. main'e gönderin ve CI'ın yeşil olmasını bekleyin.
3. Etiketleyin: `git tag v0.2.4 && git push origin v0.2.4`.

Yayımlanmış bir sürüm değiştirilemez; bir düzeltme yeni bir sürümdür. Etiket
yalnız `vX.Y.Z` biçiminde olabilir (şimdilik ön sürüm yok) ve sürüm, yayımlanmış
her sürümden yeni olmalıdır. Aynı dosyaları yerelde görmek için:

```sh
./gradlew publishAllPublicationsToVerifyRepository                               # build/verify-repo altına
./gradlew publishAllPublicationsToRewloyRepoRepository -PrewloyRepoDir=../maven  # bir Rewloy/maven klonuna; elle göndermeyin
```

Maven Central yapılandırması (`publishToMavenCentral`, imza) yerinde duruyor
ama kullanılmıyor (DECISIONS 32).

## Belgeler

| | |
|---|---|
| Başlarken | https://rewloy.com/gelistiriciler |
| API referansı | https://rewloy.com/gelistiriciler/api |
| OpenAPI 3.1 | https://app.rewloy.com/v1/openapi.json |
| Hata kodları | https://rewloy.com/gelistiriciler/hatalar |
| API'nin değişiklik günlüğü | https://rewloy.com/gelistiriciler/degisiklikler |
| Bu kütüphanenin değişiklikleri | [CHANGELOG.md](CHANGELOG.md) |

**Sürümler:**
- Kütüphane anlamsal sürümleme ([SemVer](https://semver.org)) kullanır. 1.0'a
  kadar arayüzü değişebilir.
- API'ye alan eklemek geriye uyumludur; kütüphanenin sınıfları her gün
  güncellenir ve yeni alanlar o güne kadar `additionalProperties`te görünür.
- Kalkacak bir uç nokta en az 180 gün önce duyurulur ve bu süre boyunca
  `Deprecation` ve `Sunset` başlıklarını taşır.

## Güvenlik

Bir güvenlik açığı bulursanız [SECURITY.md](SECURITY.md) dosyasındaki yoldan
özel olarak bildirin. Lütfen herkese açık issue açmayın.

## Lisans

[MIT](LICENSE)

---

## English

Developer docs (in Turkish): **https://rewloy.com/gelistiriciler**.

**The official Kotlin, Java and Android library for the Rewloy API.**

> **Status: preview (0.x). The API is stable; the library's interface may
> change until 1.0.**

The documentation of the API itself is in Turkish (links above). In short:

- A **library, not an Android app**, for Android POS terminals and the new
  generation of Turkish cash registers (most of which run Android), and for any
  JVM. No Android SDK is needed to build it.
- Every operation of the API is a method named by its `operationId`, with
  classes for request bodies, queries and answers, generated from the OpenAPI
  document, which CI reads daily and regenerates from.
- **JVM 8 bytecode, Android 5.0 (API 21) and up.** The only dependency is the
  Kotlin standard library; HTTP goes through `HttpURLConnection` (there is no
  `java.net.http` on Android) behind a small `Transport` interface, and an
  optional OkHttp adapter exists.
- Blocking calls, usable from Java; an optional artifact adds `suspend` and `Flow`.
- Safe retries, `Idempotency-Key` handling, pagination, server-sent events,
  webhook signature verification, cancelling and deprecation notices.

### Install

The packages are on Rewloy's own Maven repository, **https://maven.rewloy.com**
(not Maven Central; [docs/DECISIONS.md](docs/DECISIONS.md), 32, says why). It is
served over HTTPS only; do not write the address with `http://`. The library
targets JVM 8. A Kotlin caller needs a Kotlin 2.0 or later compiler; a
Java-only one needs none.

Gradle (Kotlin DSL), `build.gradle.kts`. In Android Studio projects the
`repositories` block goes in `settings.gradle.kts`, inside
`dependencyResolutionManagement`. With `exclusiveContent`, `com.rewloy` comes
from this repository only (a package of the same name in another repository
cannot stand in for it), and this repository is asked for nothing else.

```kotlin
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven("https://maven.rewloy.com") }
        filter { includeGroup("com.rewloy") }   // com.rewloy only from here, only com.rewloy from here
    }
}

dependencies {
    implementation("com.rewloy:rewloy:0.2.4")
    implementation("com.rewloy:rewloy-okhttp:0.2.4")      // optional: an OkHttp transport
    implementation("com.rewloy:rewloy-coroutines:0.2.4")  // optional: suspend and Flow
}
```

Gradle (Groovy), `build.gradle`:

```groovy
repositories {
    mavenCentral()
    exclusiveContent {
        forRepository { maven { url = 'https://maven.rewloy.com' } }
        filter { includeGroup 'com.rewloy' }
    }
}

dependencies {
    implementation 'com.rewloy:rewloy:0.2.4'
}
```

Maven, `pom.xml`:

```xml
<repositories>
  <repository>
    <id>rewloy</id>
    <url>https://maven.rewloy.com</url>
    <snapshots><enabled>false</enabled></snapshots>
  </repository>
</repositories>

<dependencies>
  <dependency>
    <groupId>com.rewloy</groupId>
    <artifactId>rewloy</artifactId>
    <version>0.2.4</version>
  </dependency>
</dependencies>
```

Or build it from source (Gradle needs JDK 17 or later) and use the same
coordinates with `mavenLocal()`:

```sh
git clone https://github.com/Rewloy/rewloy-kotlin && cd rewloy-kotlin && ./gradlew publishToMavenLocal
```

Releasing: bump the version in `build.gradle.kts`, `RewloyVersion.CURRENT` and
CHANGELOG.md, push to main, and once CI is green push a `v*` tag;
`.github/workflows/release.yml` adds the version to https://maven.rewloy.com.
A published version is never changed. The tag is a plain `vX.Y.Z` (no
pre-releases for now), and the version must be newer than every published one.

### Use

```kotlin
val rewloy = Rewloy { apiKey(System.getenv("REWLOY_API_KEY")) }   // or staffSession(…) + merchant(…), or holderSession(…)

val card = rewloy.issuePass(IssuePassBody(programId = programId, email = email, kvkkConsent = true))
val sale = rewloy.recordSale(
    card.serial,
    RecordSaleBody(amountMinor = 4550, locationId = locationId, reference = "receipt-$receiptNo"),   // amount in the card's currency, minor units
    RequestOptions(idempotencyKey = "till3-z0187-r$receiptNo"),
)

// A gift-card spend rung up by mistake? Void it by the key it was sent with:
rewloy.passAction(
    card.serial,
    PassActionBody("spend", locationId).apply { amountMinor = 2500 },
    RequestOptions(idempotencyKey = "till3-z0187-s$receiptNo"),
)
val voided = rewloy.reverseAction(card.serial, ReverseActionBody(actionKey = "till3-z0187-s$receiptNo"))
println("${voided.undone} ${voided.restored} ${voided.balance}")   // spend 2500 and the balance again
```

```java
Rewloy rewloy = new Rewloy(RewloyOptions.builder().apiKey(key).build());
RecordSaleBody body = new RecordSaleBody(4550);   // required fields; setters for the rest
body.setLocationId(locationId);
RecordSaleData sale = rewloy.recordSale(serial, body, RequestOptions.builder().idempotencyKey("till3-z0187-r" + no).build());
```

- **Blocking.** A call waits for the answer: use a worker thread, never
  Android's main thread. The client is thread-safe: make one and share it.
- **Till.** `recordSale` writes a completed sale to a card (the card type and
  the programme's own rule decide what is written); `getPass` returns the
  card's structured fields (`programName`, `currency`, `stamps`, `points`,
  `money`, `customer`); `reverseSale` takes a refunded sale back:
  `rewloy.reverseSale(serial, ReverseSaleBody(saleKey = key))`. A void is
  `reverseAction`: it takes back a `passAction` that was a mistake (`spend`,
  `spend-points`, `redeem-stamps`, `redeem-reward`, `use`), found by the
  `Idempotency-Key` you sent with it (`actionKey`) or its `reference`; it needs
  no `Idempotency-Key` of its own, and a repeat answers `duplicate = true`:
  `rewloy.reverseAction(serial, ReverseActionBody(actionKey = key))`. A till that
  queues sales while offline sets `RecordSaleBody.occurredAt` (ISO 8601 text with
  the UTC offset, not in the future), so the card's history shows when the sale
  really happened; the queued `idempotencyKey` makes the resend safe.
  `PassActionBody` takes an optional `reference` too, and its answer,
  `PassActionData`, is a sealed class: `PassActionDataOption1` (the
  balance-card answer, `balance`) or `PassActionDataOption2` (the coupon /
  discount-card answer, `status`, `uses`, `usesLeft`); `duplicate` is on the
  base, and a `when` over the two is exhaustive.
- **`card` on write answers.** `recordSale`, `passAction`, `reverseSale` and
  `reverseAction` answer with `card`: the card after the write, the fields of
  `getPass` except `customer`, read in the same transaction (on a replay,
  `duplicate == true`, it is the card's **current** state). A key without
  `passes.read` in the card's programme gets `card == null`. `reversed == true`
  on `recordSale` / `passAction` (replays only) says the sale written under that
  key was taken back since: send a new key to write the receipt again.
  `card` and `reversed` are required fields in 0.2.4: it reads Rewloy API 1.2.0
  and later, and against 1.1.x those calls throw `INVALID_RESPONSE`. For "can I
  act now" read `card.actions[].ready`; `rewardReady` means "reward ready" only
  for stamp and points cards (always `true` on VIP, any balance on cashback and
  gift cards).
- **Recent operations.** `listPassOperations` (`listPassOperationsAll`) lists a
  card's ledger operations, newest first and paged, for a till's "last
  operations" screen: `undoWith` (`"sale/reverse"` or `"actions/reverse"`),
  `reversible` and, for this credential's own operations, `saleKey` /
  `actionKey` to pass straight to `reverseSale` / `reverseAction`.
- **Rejected `occurredAt`** is a `400 VALIDATION` whose first `details` entry has
  `reason`: `in_future`, `too_old`, `before_issue` or `invalid` (treat an unknown
  reason as `invalid`).
- **Idempotency keys.** `recordSale`, `passAction`, `sendCampaign` and
  `refundShopRedemption` need an `Idempotency-Key`: the API's OpenAPI document
  marks the header required for them, so `RequestOptions.idempotencyKey` is
  required and the call throws an `IllegalArgumentException` before sending if
  it is missing. The client never makes one up for you (a generated key would
  not survive a restart of your app). The key must be 8–64 printable ASCII
  characters (0x21–0x7E); a non-ASCII key such as `fiş-0042` is refused
  client-side, with an `IllegalArgumentException`, before anything is sent.
  Where the header is optional (for example `issuePass`) the client still
  generates a UUID and reuses it on every retry of the call. A key is unique **for good per credential**: do not use the
  receipt number alone (fiscal receipt numbers restart after the Z report) but
  register + Z number + receipt number, or a UUID stored with the sale. The
  receipt number goes in `reference`.
- **Base URL.** `Rewloy { apiKey(key); baseUrl("https://staging.example.com") }`
  or `baseUrl("https://staging.example.com/v1")`: with or without a trailing
  `/v1` (and trailing slashes), the client appends `/v1/...` itself. Default
  `https://app.rewloy.com`.
- **Test mode.** Open the test environment (panel → Developer, or
  `POST /v1/test/environment`) and use its `rwk_test_` key at the same address:
  a separate test business that sends nothing and never reaches real
  customers. Webhooks are delivered with `Rewloy-Test: 1`.
- **Arguments.** Path parameters, then the body and the query the operation
  takes, then `RequestOptions` (`idempotencyKey`, `merchant`, `timeoutMs`,
  `maxRetries`, `cancel`, `headers`).
- **Results.** A method returns the answer's data: a class, `Page<Item>` for a
  paged list, `Unit` for 204, `RewloyFile` for files, `JsonValue` for the
  OpenAPI document. Ids and timestamps are `String`s (`java.time` is missing
  from Android before 8).
- **The whole answer.** `…WithResponse` methods return `statusCode`,
  `headers`, `requestId`, `rateLimit` (`limit`, `remaining`, `resetSeconds` from
  the `RateLimit-*` headers; `null` when absent), `mode` (the `Rewloy-Mode` header: `live` or `test`; `isTestMode`) and
  `replayed` (`Idempotent-Replayed`) beside the data.
- **Pagination.** `rewloy.listCustomersAll(query)` is an `Iterable` over the
  items of every page.
- **Streams.** `rewloy.liveFeed()` is an `EventStream`: iterate it in a thread
  (`event`, `data`, `id`), `close()` it from anywhere. It reconnects with
  `Last-Event-ID` unless `reconnect = false`.
- **Cancelling.** `RequestOptions(cancel = token)` or `rewloy.withCancel(token)`;
  `token.cancel()` aborts what is in flight.

### Webhooks

Verify the **raw** body bytes with the secret shown when the webhook was created:

```kotlin
val event = Webhook.verify(rawBody, request.header("Rewloy-Signature"), secret)
```

- **Check.** `Rewloy-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256(secret,
  "<t>.<raw body>")>` is compared in constant time, and `t` must be within
  300 seconds.
- **Refusal.** On failure it throws `WebhookSignatureException` (`reason`):
  answer 400.
- **Headers.** `Rewloy-Event` is the event type. `Rewloy-Delivery` is the
  same on every retry of a delivery: deduplicate on it. Delivery is at least
  once.

`rotateWebhookSecret` gives a webhook a new secret (returned only in that
answer); the old one keeps signing for 24 hours, so `Rewloy-Signature` carries
two `v1` values and the delivery has `Rewloy-Signature-Rotating: 1`.
`Webhook.verify` tries every `v1` and every secret you pass:
`listOf(newSecret, oldSecret)`. `deleteWebhook` removes a webhook and its
delivery history for good.

A webhook object (`listWebhooks`, `getWebhook`, `setWebhookStatus`, and the
`webhook` of `createWebhook` and `rotateWebhookSecret`) always carries two
fields, each `String?` (a date-time), null when it does not apply:

- `pausedUntil`: your receiver failed twice in a row (`5xx`, `429`, a connection
  error or no answer), so the open webhook's deliveries wait until this moment
  and are then retried on their own (60 seconds). Null when it is not paused
  or the webhook is off.
- `resumableUntil`: the **rules** turned the webhook off and its pending
  deliveries are kept (until 24 hours after it closed). Turn it on before this
  moment (`setWebhookStatus(id, SetWebhookStatusBody(active = true))`) and it
  carries on where it stopped: the kept deliveries go at once and the events
  that happened meanwhile arrive too. Null while it is on, when a person or a
  key turned it off, or once the time has passed.

Also in Rewloy 1.2.0 (library 0.2.4): `createApiKey(CreateApiKeyBodyPos(kind = "pos", locationId = …, password = …))`
(a till key bound to one branch; the standard key is `CreateApiKeyBody`, and
`createApiKey` takes either); `resetTestEnvironment(ResetTestEnvironmentBody(revokeKeys = true))`
(keeps the test business, programmes and keys; revokes keys only when asked);
`listAllBatches` (every gift-card, coupon and discount code of the business,
with the `archived` state); `409 PROGRAM_ARCHIVED` when creating a code for an
archived programme.
`sendBatchLink` e-mails a code's link only while the code issues a card:
`410 BATCH_CLOSED` (stopped), `410 BATCH_EXPIRED` (past its date),
`410 BATCH_FULL` (every card given) and `409 PROGRAM_ARCHIVED` (its programme is
archived) refuse it and no mail goes; before 1.2.0 the last three were sent
anyway. The codes are in the `ErrorCode` constants (`ErrorCode.BATCH_FULL`…).

### Errors, retries, deprecations

- **Errors.** Failures throw `RewloyException` (unchecked) with `status`,
  `code` (the API's stable code, constants in `ErrorCode`), `title`, `detail`,
  `details`, `requestId`, `rateLimit` and `body`. Subclasses: `RateLimitException`
  (`retryAfterSeconds`), `RewloyConnectionException` and `RewloyTimeoutException`.
- **What is retried.** Network errors, timeouts, 429, 502–504 and
  Cloudflare's 520–524, up to `maxRetries` (default 2), with exponential
  backoff and jitter, honouring `Retry-After` up to 60 s.
- **Only when safe.** Only GET, PUT, DELETE, and POST with an
  `Idempotency-Key`, are retried; so is `409 IDEMPOTENCY_IN_PROGRESS`.
- **Deprecations.** A deprecated operation's answers carry `Deprecation`,
  `Sunset` and `Link`. The client calls your `DeprecationListener` once per
  operation (or logs to `java.util.logging`), and the generated method is
  marked `@Deprecated`.

### `PATCH` and the JDK

Android's `HttpURLConnection` sends `PATCH`; the desktop JDK's does not (12 of
the 260 operations are `PATCH`). Up to Java 11 the library works around it; from
Java 12 on, use the OkHttp transport (`com.rewloy:rewloy-okhttp`).

### Security and licence

Report vulnerabilities privately, as [SECURITY.md](SECURITY.md) says.
[MIT](LICENSE) licensed.
