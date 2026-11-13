/* QC45 EVACom 20140922, libETransport SHA256 f93941b4... ONLY.
 * Read power/current/voltage limits from the original CAN object; clamp a COPY
 * of each decoded CurrentDemand before the original CAN serializer sees it.
 * The original request, controller object, status and authorization are retained.
 * Offsets are specific to the verified ARM EABI binary, not a public C++ ABI.
 */
#define _GNU_SOURCE
#include <stdint.h>
#include <stddef.h>
#include <string.h>
#ifndef QC45_TEST
#include <dlfcn.h>
#include <unistd.h>
#endif

typedef unsigned char u8;
typedef uint32_t u32;
typedef int16_t s16;

#define CAN_TYPE "_ZTI18CanPowerElectronic"
#define CONFIGURE "_ZN24SeccResponseConfigurator28configureCurrentDemandForCanEP20CurrentDemandReqType"
#define DEMAND "_ZN24SeccResponseConfigurator13currentDemandEP17MessageHeaderTypeP20CurrentDemandReqTypeP20CurrentDemandResType"
#define REQUEST_SIZE 148

#ifdef QC45_TEST
extern void *qc45_test_lookup(const char *name);
extern void qc45_test_failure(void);
static void *lookup(const char *name) { return qc45_test_lookup(name); }
static void failure(void) { qc45_test_failure(); }
#else
static void *lookup(const char *name) { return dlsym(RTLD_NEXT, name); }
static void failure(void) {
    static const char text[] = "QC45 current-limit v6: unexpected ABI/binding; controller stopped\n";
    (void)write(2, text, sizeof(text)-1);
    _exit(126);
}
#endif

static uint16_t r16(const u8 *p) { uint16_t v; memcpy(&v,p,2); return v; }
static u32 r32(const u8 *p) { u32 v; memcpy(&v,p,4); return v; }
static void *pointer(const u8 *p) { void *v; memcpy(&v,p,sizeof(v)); return v; }

/* CAN multiplier index 0..6 means 10^-3 .. 10^3. Return milli-units,
 * rejecting invalid indices and overflow rather than interpreting them as 1. */
static u32 milli(u32 raw, unsigned index, u32 maximum) {
    static const u32 scale[] = {1,10,100,1000,10000,100000,1000000};
    if (index>6 || raw>maximum/scale[index]) return 0;
    return raw*scale[index];
}

static u8 *can_interface(void *iface) {
    void **vt;
    ptrdiff_t top;
    void *type = lookup(CAN_TYPE);
    if (!iface || !type) { failure(); return NULL; }
    vt = (void **)pointer((const u8 *)iface);
    if (!vt) { failure(); return NULL; }
    memcpy(&top, vt-2, sizeof(top));
    if (vt[-1]!=type || top!=-8) { failure(); return NULL; }
    return (u8 *)iface+top;
}

static u8 *can_config(void *config) {
    if (!config) { failure(); return NULL; }
    return can_interface(pointer((const u8 *)config+0x24));
}

static void check_can(const u8 *self) {
    if (!self) { failure(); return; }
    if (can_interface((u8 *)self+8)!=self) failure();
}

/* The explicit CAN limit is refreshed during CurrentDemand; PMaxSchedule is
 * written only during parameter discovery and can retain the 5-kW start value.
 * Use the schedule only when the explicit field is absent. Do not reinterpret
 * an invalid explicit limit as permission. This installation's native DC
 * allocator has a hard 35-kW maximum; reject values above it, including the
 * observed 50-kW shutdown default. DIN's schedule is a signed short in watts. */
static u32 budget_mw(const u8 *s) {
    s16 raw = (s16)r16(s+0x32);
    u32 schedule = r16(s+0x52);
    u32 p;
    schedule = schedule>0 && schedule<=32767 ? schedule*1000U : 0;
    if (raw<0) return 0;
    if (raw) {
        p=milli((u32)raw,s[0x5e],35000000U);
        if (!p) return 0;
    } else p=schedule;
    /* Expose exactly the budget used for clamping, in representable watts. */
    p=(p/1000U)*1000U;
    if (p>32767000U) p=(p/10000U)*10000U;
    return p;
}

static u32 rated_mv(const u8 *s) {
    u32 v=milli(r16(s+0x34),s[0x5d],600000U);
    return v>=50000U ? v : 0;
}

static u32 present_mv(const u8 *s) {
    u32 raw=r16(s+0x44),v;
    if (!raw) return rated_mv(s); /* Before precharge, use rated voltage. */
    v=milli(raw,s[0x62],600000U);
    return v>=50000U ? v : 0;
}

/* Original PhysicalValueType: multiplier byte0, unit word4, value short8,
 * unit-present byte12. CurrentDemand target current is at24, voltage at128. */
static u32 request_value(const u8 *p, u32 unit, u32 maximum) {
    int exponent=(int)(int8_t)p[0];
    s16 raw=(s16)r16(p+8);
    if (raw<0 || exponent< -3 || exponent>3 || !(p[12]&1) || r32(p+4)!=unit) return 0;
    return milli((u32)raw,(unsigned)(exponent+3),maximum);
}

static u32 current_cap(const u8 *s, const u8 *request) {
    u32 p=budget_mw(s),v=present_mv(s),hardware,cap;
    if (!p || !v) return 0;
    if (request) {
        u32 target=request_value(request+128,5,600000U);
        if (target<50000U) return 0;
        /* EVTargetVoltage is a voltage setpoint/ceiling, not the measured
         * battery voltage used for the power-to-current conversion. Keeping
         * that larger value pinned the 27-kW ramp at about 25 kW. */
    }
    hardware=milli(r16(s+0x46),s[0x5c],200000U)/1000U;
    if (!hardware) return 0;
    /* Recalculate at every demand, with 1 V above the sampled voltage.
     * At the strict 5-kW startup/test budget also leave the observed 1.5-A
     * converter excursion. At higher budgets whole-amp rounding provides
     * the current ceiling; a fixed extra 1.5 A can itself consume more than
     * the native 1-kW catchup allowance after converter underdelivery.
     * mW / mV = A. Before precharge use the rated voltage instead. */
    v+=1000U;
    if (p<=5000000U) {
        if (2U*p<=3U*v) return 0;
        cap=(2U*p-3U*v)/(2U*v);
    } else cap=p/v;
    return cap<hardware ? cap : hardware;
}

static void physical(u8 *p, u32 value, u32 unit, int exponent) {
    int8_t e=(int8_t)exponent;
    s16 v=(s16)value;
    memcpy(p,&e,1); memcpy(p+4,&unit,4); memcpy(p+8,&v,2);
    p[12]|=1;
}

static s16 power_value(const u8 *s) {
    u32 watts=budget_mw(s)/1000U;
    return (s16)(watts>32767U ? watts/10U : watts);
}
static int8_t power_exponent(const u8 *s) { return budget_mw(s)>32767000U ? 1 : 0; }

s16 _ZN18CanPowerElectronic24getEVSEMaximumPowerLimitEv(const u8 *s) {
    check_can(s); return power_value(s);
}
int8_t _ZN18CanPowerElectronic34getEVSEMaximumPowerLimitMultiplierEv(const u8 *s) {
    check_can(s); return power_exponent(s);
}
s16 _ZThn8_N18CanPowerElectronic24getEVSEMaximumPowerLimitEv(const u8 *i) {
    return _ZN18CanPowerElectronic24getEVSEMaximumPowerLimitEv(i-8);
}
int8_t _ZThn8_N18CanPowerElectronic34getEVSEMaximumPowerLimitMultiplierEv(const u8 *i) {
    return _ZN18CanPowerElectronic34getEVSEMaximumPowerLimitMultiplierEv(i-8);
}
s16 _ZN18CanPowerElectronic26getEVSEMaximumCurrentLimitEv(const u8 *s) {
    check_can(s); return (s16)current_cap(s,NULL);
}
s16 _ZThn8_N18CanPowerElectronic26getEVSEMaximumCurrentLimitEv(const u8 *i) {
    return _ZN18CanPowerElectronic26getEVSEMaximumCurrentLimitEv(i-8);
}

int _ZN24SeccResponseConfigurator28configureCurrentDemandForCanEP20CurrentDemandReqType(void *config, const u8 *request) {
    typedef int (*original_type)(void *,const void *);
    original_type original=(original_type)lookup(CONFIGURE);
    u8 copy[REQUEST_SIZE] __attribute__((aligned(8)));
    u8 *s;
    u32 cap,wanted;
    if (!original || !request) { failure(); return -1; }
    s=can_config(config);
    cap=current_cap(s,request);
    wanted=request_value(request+24,3,1000000U);
    memcpy(copy,request,sizeof(copy));
    if (!wanted) physical(copy+24,0,3,0);
    else if (wanted>cap*1000U) physical(copy+24,cap,3,0);
    return original(config,copy);
}

int _ZN24SeccResponseConfigurator13currentDemandEP17MessageHeaderTypeP20CurrentDemandReqTypeP20CurrentDemandResType(
    void *config,void *header,const u8 *request,u8 *response) {
    typedef int (*original_type)(void *,void *,const void *,void *);
    original_type original=(original_type)lookup(DEMAND);
    u8 *s=can_config(config);
    u32 cap,wanted,actual,p;
    int result,limited;
    if (!original || !request || !response) { failure(); return -1; }
    result=original(config,header,request,response);
    if (result) return result;
    cap=current_cap(s,request);
    wanted=request_value(request+24,3,1000000U);
    actual=milli(r16(s+0x3e),s[0x63],200000U);
    p=budget_mw(s);
    physical(response+0x54,cap,3,0);
    response[0x74]|=2; /* EVSEMaximumCurrentLimit present */
    if (p) {
        physical(response+0x64,(u32)(uint16_t)power_value(s),7,power_exponent(s));
        response[0x74]|=4;
    } else response[0x74]&=(u8)~4;
    limited=cap==0 || wanted>=cap*1000U || actual>=cap*1000U;
    if (limited) {
        u32 yes=1;
        memcpy(response+0x38,&yes,4); /* CurrentLimitAchieved; preserve OEM true */
        memcpy(response+0x40,&yes,4); /* PowerLimitAchieved was hard-coded false */
    }
    return result;
}

unsigned qc45_current_limit_version(void) { return 6; }
unsigned qc45_current_limit_bindings_ok(void) {
    return lookup(CAN_TYPE)!=NULL && lookup(CONFIGURE)!=NULL && lookup(DEMAND)!=NULL;
}
