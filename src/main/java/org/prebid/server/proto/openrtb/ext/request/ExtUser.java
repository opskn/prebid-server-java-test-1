package org.prebid.server.proto.openrtb.ext.request;

import lombok.AllArgsConstructor;
import lombok.Value;

/**
 * Defines the contract for bidrequest.user.ext
 */
@AllArgsConstructor(staticName = "of")
@Value
public class ExtUser {

    ExtUserDigiTrust digitrust;
}
