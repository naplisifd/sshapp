package com.sshapp.data

/** A curated command. Placeholders look like `<service>` and must be filled in before running. */
data class CatalogCommand(val command: String, val description: String, val category: String) {
    val hasPlaceholder: Boolean get() = PLACEHOLDER.containsMatchIn(command)

    companion object {
        val PLACEHOLDER = Regex("<[a-z_-]+>")
    }
}

/** Frequently used Ubuntu Server administration commands, grouped by task. */
object CommandCatalog {
    private fun group(category: String, vararg items: Pair<String, String>) =
        items.map { CatalogCommand(it.first, it.second, category) }

    val all: List<CatalogCommand> = listOf(
        group(
            "System",
            "uptime" to "How long the server has been up, and load average",
            "hostnamectl" to "Hostname, OS, kernel and hardware summary",
            "cat /etc/os-release" to "Ubuntu release details",
            "free -h" to "Memory and swap usage",
            "df -h" to "Disk space per filesystem",
            "top -bn1 | head -20" to "Snapshot of top processes",
            "who" to "Users currently logged in",
            "last -n 10" to "Recent logins",
            "sudo reboot" to "Reboot the server",
        ),
        group(
            "Packages",
            "sudo apt update" to "Refresh package lists",
            "apt list --upgradable" to "Packages with pending updates",
            "sudo apt upgrade -y" to "Install all available updates",
            "sudo apt install <package>" to "Install a package",
            "sudo apt remove <package>" to "Remove a package",
            "apt search <term>" to "Search the package index",
            "sudo apt autoremove -y" to "Remove unused dependencies",
            "dpkg -l | grep <name>" to "Check if a package is installed",
            "snap list" to "Installed snaps",
            "cat /var/run/reboot-required.pkgs" to "Packages that need a reboot",
        ),
        group(
            "Services",
            "systemctl status <service>" to "Status and recent log lines of a service",
            "sudo systemctl restart <service>" to "Restart a service",
            "sudo systemctl stop <service>" to "Stop a service",
            "sudo systemctl enable --now <service>" to "Enable at boot and start now",
            "systemctl list-units --type=service --state=running" to "Running services",
            "systemctl --failed" to "Services that failed",
            "journalctl -u <service> -n 100 --no-pager" to "Last 100 log lines of a service",
        ),
        group(
            "Logs",
            "journalctl -p err -b --no-pager" to "Errors since last boot",
            "journalctl -f" to "Follow the system journal",
            "sudo tail -n 100 /var/log/syslog" to "Recent syslog entries",
            "sudo dmesg -T | tail -50" to "Kernel messages",
            "sudo tail -n 50 /var/log/auth.log" to "Recent authentication events",
        ),
        group(
            "Disk",
            "du -sh * | sort -h" to "Size of each item in the current folder",
            "sudo du -h --max-depth=1 / 2>/dev/null | sort -h" to "Largest top-level folders",
            "lsblk" to "Block devices and partitions",
            "sudo find / -xdev -type f -size +500M 2>/dev/null" to "Files larger than 500 MB",
            "ls -lah" to "Detailed listing including hidden files",
            "sudo journalctl --vacuum-time=7d" to "Shrink the journal to 7 days",
        ),
        group(
            "Network",
            "ip -br a" to "Network interfaces and addresses",
            "ss -tulpn" to "Listening ports and owning processes",
            "sudo ufw status verbose" to "Firewall rules",
            "sudo ufw allow <port>/tcp" to "Open a TCP port in the firewall",
            "ping -c 4 <host>" to "Check connectivity to a host",
            "curl -I <url>" to "Fetch HTTP headers",
            "ip route" to "Routing table",
            "resolvectl status" to "DNS configuration",
            "sudo netplan apply" to "Apply netplan network config",
        ),
        group(
            "Processes",
            "ps aux --sort=-%cpu | head -15" to "Top CPU consumers",
            "ps aux --sort=-%mem | head -15" to "Top memory consumers",
            "htop" to "Interactive process viewer (use raw keys)",
            "pgrep -a <name>" to "Find processes by name",
            "kill <pid>" to "Terminate a process",
        ),
        group(
            "Users",
            "whoami" to "Current user",
            "id" to "User and group IDs",
            "sudo adduser <user>" to "Create a user",
            "sudo usermod -aG sudo <user>" to "Give a user sudo rights",
            "sudo passwd <user>" to "Change a user's password",
            "sudo fail2ban-client status sshd" to "Banned SSH attackers (fail2ban)",
        ),
        group(
            "Docker",
            "docker ps" to "Running containers",
            "docker ps -a" to "All containers",
            "docker compose up -d" to "Start compose stack in background",
            "docker compose down" to "Stop compose stack",
            "docker compose logs -f --tail=100" to "Follow compose logs",
            "docker logs -f --tail=100 <container>" to "Follow a container's logs",
            "docker exec -it <container> bash" to "Shell into a container",
            "docker system df" to "Docker disk usage",
            "docker system prune" to "Remove unused containers, networks and images",
        ),
    ).flatten()

    val categories: List<String> = all.map { it.category }.distinct()

    /** Shown as quick chips before the user has any history on a host. */
    val essentials = listOf("df -h", "free -h", "systemctl --failed", "sudo apt update", "ss -tulpn", "docker ps")
        .mapNotNull { c -> all.firstOrNull { it.command == c } }
}
