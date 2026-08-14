package com.yjiyun.chatflows.runtime.security;
import java.nio.charset.StandardCharsets; import java.security.MessageDigest; import java.util.Set;
/** Split regular/admin credentials; intentionally mirrors agent-manager (A23). */
public final class AuthService {
 private final byte[] runtimeToken,adminToken;
 public AuthService(String runtimeToken,String adminToken){this.runtimeToken=token(runtimeToken,"runtime");this.adminToken=token(adminToken,"admin");if(MessageDigest.isEqual(this.runtimeToken,this.adminToken))throw new IllegalArgumentException("runtime and admin tokens must differ");}
 public void require(String authorization){requireToken(authorization,runtimeToken);}
 public void requireRole(String authorization,String role,String... allowed){if("admin".equals(role))requireToken(authorization,adminToken);else requireToken(authorization,runtimeToken);if(role==null||!Set.of(allowed).contains(role))throw new ForbiddenException("role not allowed");}
 public void requireAdmin(String authorization,String role){requireToken(authorization,adminToken);if(!"admin".equals(role))throw new ForbiddenException("admin role required");}
 private static byte[] token(String value,String kind){if(value==null||value.length()<16)throw new IllegalArgumentException(kind+" token must be >=16 chars");return value.getBytes(StandardCharsets.UTF_8);}
 private static void requireToken(String authorization,byte[] expected){String value=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):"";if(!MessageDigest.isEqual(expected,value.getBytes(StandardCharsets.UTF_8)))throw new SecurityException("unauthorized");}
}
