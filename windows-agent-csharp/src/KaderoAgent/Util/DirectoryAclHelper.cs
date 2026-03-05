namespace KaderoAgent.Util;

using System.Security.AccessControl;
using System.Security.Principal;
using Microsoft.Extensions.Logging;

public static class DirectoryAclHelper
{
    /// <summary>
    /// Ensures directory has restricted ACL: only SYSTEM and Administrators have full control.
    /// Removes inheritance and all other user permissions.
    /// </summary>
    public static void SecureDirectory(string path, ILogger? logger = null)
    {
        if (!Directory.Exists(path))
            Directory.CreateDirectory(path);

        try
        {
            var dirInfo = new DirectoryInfo(path);
            var security = dirInfo.GetAccessControl();

            // Remove inheritance
            security.SetAccessRuleProtection(isProtected: true, preserveInheritance: false);

            // Remove all existing rules
            foreach (FileSystemAccessRule rule in security.GetAccessRules(true, true, typeof(SecurityIdentifier)))
            {
                security.RemoveAccessRule(rule);
            }

            // Add SYSTEM = FullControl
            security.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(WellKnownSidType.LocalSystemSid, null),
                FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None,
                AccessControlType.Allow));

            // Add Administrators = FullControl
            security.AddAccessRule(new FileSystemAccessRule(
                new SecurityIdentifier(WellKnownSidType.BuiltinAdministratorsSid, null),
                FileSystemRights.FullControl,
                InheritanceFlags.ContainerInherit | InheritanceFlags.ObjectInherit,
                PropagationFlags.None,
                AccessControlType.Allow));

            dirInfo.SetAccessControl(security);
            logger?.LogInformation("Secured directory ACL: {Path} (SYSTEM + Administrators only)", path);
        }
        catch (Exception ex)
        {
            logger?.LogWarning(ex, "Failed to set ACL on {Path}. May require elevated permissions.", path);
        }
    }
}
