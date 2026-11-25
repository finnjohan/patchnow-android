# Changelog

## 1.2.0
- More programs under "Software you run": VMware vSphere (ESX, vCenter, Cloud Foundation, NSX, Aria, VMware Tools), Proxmox (VE, Backup Server), MySQL/MariaDB, Jenkins (including plugins) and GitLab. VMware Spring/Tanzu/Horizon and Workstation/Fusion on their own are not counted, and neither are MySQL Connectors or SQL injections in web apps that mention MySQL.

## 1.1.0
- New setting "Software you run": PHP, Drupal, CakePHP, nginx, Java (JDK/JRE) and Apache HTTP Server. Critical vulnerabilities (CVSS 9 or higher) in the programs you turn on are always shown and notified, even before they are exploited, and even if the vendor is hidden or the platform is turned off. Exploited ones (CISA KEV) and CERT-SE warnings about them are always critical.
- Matching uses the NVD's list of affected products (CPE, including Drupal modules), and the description when the NVD has not listed the products yet.
- Better titles for new vulnerabilities that only have a CVE number.

## 1.0.0
- First Android version: actively exploited and critical vulnerabilities from CISA KEV, NVD, EPSS and CERT-SE, three levels, platform filter, notifications with quiet hours, background check, mark as handled, hide vendors. English and Swedish, light/dark theme.
