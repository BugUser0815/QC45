#define QC45_TEST
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <setjmp.h>
#include "current_limit.c"

static int typeinfo;
static void *vtable[4];
static u8 object[256] __attribute__((aligned(8)));
static u8 config[64] __attribute__((aligned(8)));
static u8 sent[REQUEST_SIZE];
static int calls;
static jmp_buf expected_failure;
static int catching_failure;

void qc45_test_failure(void) {
    if (catching_failure) longjmp(expected_failure,1);
    fputs("unexpected ABI failure\n",stderr); abort();
}

static void w16(u8 *p,unsigned v) { uint16_t a=v;memcpy(p,&a,2); }
static void ptr(u8 *p,void *v) { memcpy(p,&v,sizeof(v)); }
static int original_config(void *c,const void *r) {
    assert(c==config);memcpy(sent,r,REQUEST_SIZE);calls++;return 0;
}
static int original_demand(void *c,void *h,const void *r,void *response) {
    u8 *p=response;(void)h;
    _ZN24SeccResponseConfigurator28configureCurrentDemandForCanEP20CurrentDemandReqType(c,r);
    memset(p,0,120); physical(p+0x54,125,3,0);p[0x74]=6;return 0;
}
void *qc45_test_lookup(const char *name) {
    if (!strcmp(name,CAN_TYPE)) return &typeinfo;
    if (!strcmp(name,CONFIGURE)) return (void *)original_config;
    if (!strcmp(name,DEMAND)) return (void *)original_demand;
    return NULL;
}

static void setup(unsigned watts,unsigned voltage_tenths) {
    memset(object,0,sizeof(object));memset(config,0,sizeof(config));
    vtable[0]=(void *)(intptr_t)-8;vtable[1]=&typeinfo;
    ptr(object+8,vtable+2);ptr(config+0x24,object+8);
    w16(object+0x52,watts);w16(object+0x34,500);object[0x5d]=3;
    w16(object+0x46,125);object[0x5c]=3;
    w16(object+0x44,voltage_tenths);object[0x62]=2;
}
static void request(u8 *r,unsigned current,unsigned voltage) {
    memset(r,0x5a,REQUEST_SIZE);
    physical(r+24,current,3,0);physical(r+128,voltage,5,0);
}

int main(void) {
    u8 r[REQUEST_SIZE],before[REQUEST_SIZE],state[sizeof(object)],response[120];
    setup(5000,3731);
    assert(budget_mw(object)==5000000);
    assert(_ZN18CanPowerElectronic26getEVSEMaximumCurrentLimitEv(object)==11);
    assert(_ZThn8_N18CanPowerElectronic26getEVSEMaximumCurrentLimitEv(object+8)==11);
    w16(object+0x46,1250);object[0x5c]=2;
    assert(_ZN18CanPowerElectronic26getEVSEMaximumCurrentLimitEv(object)==11);
    request(r,40,390);memcpy(before,r,sizeof(r));memcpy(state,object,sizeof(state));
    assert(current_cap(object,r)==11);
    /* Recorded converter excursion was +1.5 A at 373 V. The low-budget
     * reserve must leave room for that excursion in this vehicle's test. */
    assert((current_cap(object,r)*1000U+1500U)*373U<=5000000U);
    assert(_ZN24SeccResponseConfigurator13currentDemandEP17MessageHeaderTypeP20CurrentDemandReqTypeP20CurrentDemandResType(config,NULL,r,response)==0);
    assert(request_value(sent+24,3,1000000)==11000);
    assert(request_value(response+0x54,3,1000000)==11000);
    assert(r32(response+0x38)==1 && r32(response+0x40)==1);
    assert(request_value(response+0x64,7,50000000)==5000000);
    assert(!memcmp(r,before,sizeof(r)) && !memcmp(state,object,sizeof(state)));

    request(r,0,390);original_demand(config,NULL,r,response);
    assert(request_value(sent+24,3,1000000)==0);
    request(r,5,390);original_demand(config,NULL,r,response);
    assert(request_value(sent+24,3,1000000)==5000);
    physical(r+24,125,3,-1);original_demand(config,NULL,r,response);
    assert(request_value(sent+24,3,1000000)==11000);
    physical(r+24,5,3,-1);original_demand(config,NULL,r,response);
    assert(request_value(sent+24,3,1000000)==500);
    physical(r+24,40,5,0);original_demand(config,NULL,r,response);
    assert(request_value(sent+24,3,1000000)==0);
    request(r,40,390);physical(r+128,390,7,0);original_demand(config,NULL,r,response);
    assert(request_value(sent+24,3,1000000)==0);

    setup(5000,0);assert(current_cap(object,NULL)==8);
    setup(6000,3731);request(r,40,390);assert(current_cap(object,r)==16);
    setup(5000,1);assert(current_cap(object,NULL)==0);
    setup(5000,3731);object[0x62]=7;assert(current_cap(object,NULL)==0);
    setup(5000,3731);object[0x5c]=255;assert(current_cap(object,NULL)==0);
    setup(0,3731);assert(current_cap(object,NULL)==0);
    setup(65535,3731);assert(current_cap(object,NULL)==0);
    setup(5000,3731);w16(object+0x32,5000);object[0x5e]=4;
    assert(budget_mw(object)==0); /* shutdown's 50-kW default cannot authorize current */
    setup(0,3731);w16(object+0x32,5000);object[0x5e]=4;
    assert(budget_mw(object)==0);
    /* The discovery-only schedule stays at 5 kW while live power ramps in
     * both directions. Exercise the full response and CAN path, not only a
     * getter, including signed-short power representation above 32,767 W. */
    const unsigned limits[]={5000,6000,7000,12000,25000,33000,35000,12000,6000,5000};
    for(unsigned i=0;i<sizeof(limits)/sizeof(limits[0]);i++) {
        unsigned watts=limits[i];
        setup(5000,3731);request(r,125,390);
        w16(object+0x32,watts>32767 ? watts/10 : watts);
        object[0x5e]=watts>32767 ? 4 : 3;
        memcpy(before,r,sizeof(r));memcpy(state,object,sizeof(state));
        assert(budget_mw(object)==watts*1000U);
        assert(_ZN24SeccResponseConfigurator13currentDemandEP17MessageHeaderTypeP20CurrentDemandReqTypeP20CurrentDemandResType(config,NULL,r,response)==0);
        assert(request_value(response+0x64,7,50000000)==watts*1000U);
        assert(request_value(sent+24,3,1000000)==request_value(response+0x54,3,1000000));
        assert((uint64_t)request_value(sent+24,3,1000000)*374100U<=(uint64_t)watts*1000000U);
        if (watts<=5000) assert((uint64_t)(request_value(sent+24,3,1000000)+1500U)*374100U<=(uint64_t)watts*1000000U);
        assert(!memcmp(r,before,sizeof(r)) && !memcmp(state,object,sizeof(state)));
    }
    setup(5000,3731);w16(object+0x32,65535);assert(budget_mw(object)==0);
    setup(5000,3731);w16(object+0x32,5000);object[0x5e]=7;assert(budget_mw(object)==0);

    /* Recorded 27-kW ramp at 383 V / 400-V setpoint must clear the native
     * 26-kW catchup threshold without relaxing that threshold. */
    setup(5000,3830);w16(object+0x32,27000);object[0x5e]=3;
    request(r,125,400);assert(current_cap(object,r)==70);
    assert(current_cap(object,r)*383U>=26000U);
    setup(5000,3840);w16(object+0x32,3300);object[0x5e]=4;
    assert(current_cap(object,r)==85 && current_cap(object,r)*384U>=32000U);
    /* The high-power ramp must tolerate the converter's measured current
     * underdelivery while retaining the native one-kW catchup threshold.
     * Check every kW stage and the Sprinter voltage range, with -1 A actual
     * response, without changing native allocator/guard thresholds. */
    for (unsigned watts=6000;watts<=33000;watts+=1000) {
        for (unsigned v10=3700;v10<=4300;v10++) {
            setup(5000,v10);request(r,125,450);
            w16(object+0x32,watts>32767 ? watts/10 : watts);
            object[0x5e]=watts>32767 ? 4 : 3;
            unsigned cap=current_cap(object,r);
            assert(cap>1 && (cap-1U)*v10>=(watts-1000U)*10U);
            assert((uint64_t)cap*(v10*100U+1000U)<=(uint64_t)watts*1000U);
        }
    }
    /* Independent nominal physical bound and immutability checks. The
     * low-budget reserve model covers +1.5 A and +1 V above each sample;
     * the target voltage is a separate CCCV setpoint, not measured output. */
    unsigned seed=0x45;
    for(unsigned i=0;i<100000;i++) {
        seed=1664525U*seed+1013904223U;unsigned watts=1+seed%32767;
        seed=1664525U*seed+1013904223U;unsigned v10=500+seed%4501;
        seed=1664525U*seed+1013904223U;unsigned target=50+seed%451;
        seed=1664525U*seed+1013904223U;unsigned amps=seed%501;
        setup(watts,v10);
        /* Half the cases retain a stale 5-kW schedule and exercise a live
         * explicit budget instead of the schedule fallback. */
        if (i&1) { w16(object+0x52,5000);w16(object+0x32,watts);object[0x5e]=3; }
        request(r,amps,target);memcpy(before,r,sizeof(r));memcpy(state,object,sizeof(state));
        original_demand(config,NULL,r,response);
        u32 sent_ma=request_value(sent+24,3,1000000);
        u32 voltage_mv=v10*100;
        assert(sent_ma<=amps*1000 && sent_ma<=125000);
        assert((uint64_t)sent_ma*voltage_mv<=(uint64_t)watts*1000000);
        if (sent_ma && watts<=5000U) assert((uint64_t)(sent_ma+1500U)*(voltage_mv+1000U)<=(uint64_t)watts*1000000);
        assert(!memcmp(r,before,sizeof(r)) && !memcmp(state,object,sizeof(state)));
        for(unsigned j=0;j<REQUEST_SIZE;j++)
            if (j<24 || j>=40) assert(sent[j]==r[j]);
    }
    setup(5000,3731);catching_failure=1;vtable[1]=NULL;
    if (!setjmp(expected_failure)) { check_can(object);assert(!"RTTI mismatch accepted"); }
    catching_failure=0;
    printf("{\"random_physical_bound_checks\":100000,\"can_calls\":%d,\"dynamic_limit_steps\":10,\"high_power_catchup_model_cases\":16828,\"zero_and_fractional_requests\":\"passed\",\"invalid_inputs\":\"closed\",\"request_and_object_immutable\":true,\"ABI_mismatch_rejected\":true}\n",calls);
    return 0;
}
