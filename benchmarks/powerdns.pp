# From https://github.com/antonlindstrom/puppet-powerdns/tree/899651549b6d57470d233321265ace862270e154
# Adapted to use define types instead of classes.
# Combined into one file.
# Some branching removed.

$powerdns::params::package = 'pdns-server'
$powerdns::params::package_provider = 'dpkg'
$powerdns::params::cfg_include_path = '/etc/powerdns/pdns.d'

define powerdns::package(
  $package = $powerdns::params::package,
  $ensure = 'present',
  $source = undef,
  $purge_config = 'false'
) {
  $package_source = $source
  $package_provider = $powerdns::params::package_provider

  package { $package:
    ensure => $ensure,
    source => $package_source,
    provider => $package_provider
  }

  $owner = 'root'
  $group = 'root'
  $mode = '0755'
  file { $powerdns::params::cfg_include_path :
    ensure => directory,
    owner => $owner,
    group => $group,
    mode => $mode,
    recurse => $purge_config,
    purge => $purge_config,
  }

  file { "$powerdns::params::cfg_include_path/authoritive.conf":
    ensure  => present,
    content => "content omitted",
    owner   => $owner,
    group   => $group,
  }
}

define powerdns($ensure = 'present', $source = '', $purge_config = 'false') {
  powerdns::package {
    ensure => $ensure,
    source => $source,
    purge_config => $purge_config
  }
}

powerdns {
  ensure => present
}