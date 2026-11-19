/*
 * IVO Mirror · receba a tela do iPhone no IVO LIVE
 * Copyright (C) 2026 evilgreenIvo
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version. See LICENSE.
 *
 * The network announcement ("I'm a screen for AirPlay") on Android. UxPlay announces itself with
 * Avahi or Bonjour, which apps can't use on Android; this builds the same TXT records UxPlay's
 * dns_sd backend builds (same keys and values, from dnssdint.h) and hands them to the app, which
 * announces them with Android's own NsdManager (bridge.c → ReceiverService).
 */
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <assert.h>

#include "dnssd.h"
#include "dnssdint.h"
#include "utils.h"

/* Set by bridge.c: announces (port > 0) or withdraws (port == 0) one service. */
void android_dnssd_announce(const char *type, const char *name, unsigned short port, const char *txt, int txt_len);

#define TXT_MAX 1024

typedef struct {
    char raop_txt[TXT_MAX];
    int raop_len;
    char airplay_txt[TXT_MAX];
    int airplay_len;
    char raop_name[256];
} dnssd_android_t;

/* One "key=value" entry: a length byte, then the bytes (the DNS TXT format). */
static void txt_add(char *buf, int *len, const char *key, const char *value) {
    int kl = (int) strlen(key);
    int vl = (int) strlen(value);
    int n = kl + 1 + vl;
    if (n > 255 || *len + 1 + n > TXT_MAX) return;
    buf[(*len)++] = (char) n;
    memcpy(buf + *len, key, kl); *len += kl;
    buf[(*len)++] = '=';
    memcpy(buf + *len, value, vl); *len += vl;
}

void *dnssd_private_init(dnssd_t *dnssd_public, int *error) {
    (void) dnssd_public;
    dnssd_android_t *d = calloc(1, sizeof(dnssd_android_t));
    if (!d && error) *error = DNSSD_ERROR_OUTOFMEM;
    return d;
}

void dnssd_private_destroy(void *dnssd_private) {
    free(dnssd_private);
}

void dnssd_error_text(int *error, const char *appname) {
    (void) error; (void) appname;
}

int dnssd_register_raop(dnssd_t *dnssd_public, unsigned short port) {
    assert(dnssd_public && dnssd_public->dnssd_private);
    dnssd_android_t *d = dnssd_public->dnssd_private;
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);
    d->raop_len = 0;
    char *t = d->raop_txt;
    int *n = &d->raop_len;
    txt_add(t, n, "ch", RAOP_CH);
    txt_add(t, n, "cn", RAOP_CN);
    txt_add(t, n, "da", RAOP_DA);
    txt_add(t, n, "et", RAOP_ET);
    txt_add(t, n, "vv", RAOP_VV);
    txt_add(t, n, "ft", features);
    txt_add(t, n, "am", GLOBAL_MODEL);
    txt_add(t, n, "md", RAOP_MD);
    txt_add(t, n, "rhd", RAOP_RHD);
    txt_add(t, n, "pw", "false");
    txt_add(t, n, "sf", dnssd_public->pin_pw == 1 ? "0x8c" : RAOP_SF);
    txt_add(t, n, "sr", RAOP_SR);
    txt_add(t, n, "ss", RAOP_SS);
    txt_add(t, n, "sv", RAOP_SV);
    txt_add(t, n, "tp", RAOP_TP);
    txt_add(t, n, "txtvers", RAOP_TXTVERS);
    txt_add(t, n, "vs", RAOP_VS);
    txt_add(t, n, "vn", RAOP_VN);
    txt_add(t, n, "pk", dnssd_public->pk ? dnssd_public->pk : "");

    /* The AirTunes service's name is "hwaddr@name". */
    char servname[256];
    if (utils_hwaddr_raop(servname, sizeof(servname), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) return -1;
    strncat(servname, "@", sizeof(servname) - strlen(servname) - 1);
    strncat(servname, dnssd_public->name, sizeof(servname) - strlen(servname) - 1);
    strncpy(d->raop_name, servname, sizeof(d->raop_name) - 1);
    android_dnssd_announce("_raop._tcp", servname, port, d->raop_txt, d->raop_len);
    return 0;
}

int dnssd_register_airplay(dnssd_t *dnssd_public, unsigned short port) {
    assert(dnssd_public && dnssd_public->dnssd_private);
    dnssd_android_t *d = dnssd_public->dnssd_private;
    char device_id[3 * MAX_HWADDR_LEN];
    char features[22] = {0};
    snprintf(features, sizeof(features), "0x%X,0x%X", dnssd_public->features1, dnssd_public->features2);
    if (utils_hwaddr_airplay(device_id, sizeof(device_id), dnssd_public->hw_addr, dnssd_public->hw_addr_len) < 0) return -1;
    d->airplay_len = 0;
    char *t = d->airplay_txt;
    int *n = &d->airplay_len;
    txt_add(t, n, "deviceid", device_id);
    txt_add(t, n, "features", features);
    txt_add(t, n, "pw", "false");
    /* 0x8 = PIN required (the iPhone shows the code entry). */
    txt_add(t, n, "flags", dnssd_public->pin_pw == 1 ? "0x8c" : "0x4");
    txt_add(t, n, "model", GLOBAL_MODEL);
    txt_add(t, n, "pk", dnssd_public->pk ? dnssd_public->pk : "");
    txt_add(t, n, "pi", AIRPLAY_PI);
    txt_add(t, n, "srcvers", AIRPLAY_SRCVERS);
    txt_add(t, n, "vv", AIRPLAY_VV);
    android_dnssd_announce("_airplay._tcp", dnssd_public->name, port, d->airplay_txt, d->airplay_len);
    return 0;
}

void dnssd_unregister_raop(dnssd_t *dnssd_public) {
    dnssd_android_t *d = dnssd_public->dnssd_private;
    android_dnssd_announce("_raop._tcp", d ? d->raop_name : "", 0, NULL, 0);
}

void dnssd_unregister_airplay(dnssd_t *dnssd_public) {
    android_dnssd_announce("_airplay._tcp", dnssd_public->name, 0, NULL, 0);
}

const char *dnssd_get_raop_txt(dnssd_t *dnssd_public, int *length) {
    dnssd_android_t *d = dnssd_public->dnssd_private;
    *length = d->raop_len;
    return d->raop_txt;
}

const char *dnssd_get_airplay_txt(dnssd_t *dnssd_public, int *length) {
    dnssd_android_t *d = dnssd_public->dnssd_private;
    *length = d->airplay_len;
    return d->airplay_txt;
}

/* NsdManager serves the announcement itself: nothing for the library to service. */
int dnssd_get_service_fd(dnssd_t *dnssd, int service) { (void) dnssd; (void) service; return -1; }
int dnssd_process_service(dnssd_t *dnssd, int service) { (void) dnssd; (void) service; return 0; }
