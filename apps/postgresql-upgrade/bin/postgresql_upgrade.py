#!/usr/bin/env python3

"""
PostgreSQL upgrade script.

Usage:

time docker run -it \
    -v ~/Downloads/postgres_11_vol/:/var/lib/postgresql/ \
    gcr.io/mcback/postgresql-upgrade \
    postgresql_upgrade.py --source_version=11 --target_version=12
"""

import argparse
import dataclasses
import getpass
import glob
import logging
import multiprocessing
import os
import pathlib
import shutil
import signal
import socket
import subprocess
import time

logging.basicConfig(level=logging.DEBUG)


class PostgresUpgradeError(Exception):
    pass


POSTGRES_DATA_DIR = "/var/lib/postgresql"
POSTGRES_USER = 'postgres'


def _tcp_port_is_open(port: int, hostname: str = 'localhost') -> bool:
    sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    sock.settimeout(2)
    try:
        result = sock.connect_ex((hostname, port))
    except socket.gaierror as ex:
        logging.warning(f"Unable to resolve {hostname}: {ex}")
        return False

    if result == 0:
        try:
            sock.shutdown(socket.SHUT_RDWR)
        except OSError as ex:
            # Quiet down "OSError: [Errno 57] Socket is not connected"
            logging.warning(f"Error while shutting down socket: {ex}")

    sock.close()
    return result == 0


def _dir_exists_and_accessible(directory: str) -> bool:
    return os.path.isdir(directory) and os.access(directory, os.X_OK)


class _PostgresVersion(object):
    __slots__ = [
        'version',
        'data_dir',
        'main_dir',
        'bin_dir',
        'initdb',
        'pg_upgrade',
        'vacuumdb',
        'postgres',
        'tmp_conf_dir',
        'port',
    ]

    @classmethod
    def _current_postgresql_config_path(cls) -> str:
        conf_list = os.listdir('/etc/postgresql/')
        if len(conf_list) != 1:
            raise PostgresUpgradeError(f"More / less than one PostgreSQL configuration set has been found: {conf_list}")
        current_version = conf_list[0]
        if not current_version.isdecimal():
            raise PostgresUpgradeError(f"Invalid PostgreSQL version: {current_version}")
        current_version = int(current_version)

        current_postgresql_config_path = os.path.join('/etc/postgresql/', str(current_version), 'main')
        if not os.path.isfile(os.path.join(current_postgresql_config_path, 'postgresql.conf')):
            raise PostgresUpgradeError(f"postgresql.conf does not exist in {current_postgresql_config_path}.")

        return current_postgresql_config_path

    def __init__(self,
                 version: int,
                 target_version: bool,
                 starting_version: bool, port: int,
                 extra_postgres_config: str):
        assert isinstance(version, int), "Version number must be integer."
        self.version = version
        assert isinstance(port, int), "Port must be an integer."
        self.port = port

        self.data_dir = os.path.join(POSTGRES_DATA_DIR, str(version))
        if target_version:
            if os.path.exists(self.data_dir):
                raise PostgresUpgradeError((
                    f"New data directory {self.data_dir} already exists; if the previous attempt to upgrade failed, "
                    "run something like this:\n\n"
                    f"    rm -rf {self.data_dir}\n"
                    "\n\n"
                    "on a container, or adjust the path on the host, or revert to old ZFS snapshot."
                ))
        else:
            if starting_version:
                if not _dir_exists_and_accessible(self.data_dir):
                    raise PostgresUpgradeError((
                        f"Old data directory {self.data_dir} does not exist or is inaccessible; forgot to mount it?"
                    ))

        self.main_dir = os.path.join(self.data_dir, "main")
        if not target_version:
            if starting_version:
                if not _dir_exists_and_accessible(self.main_dir):
                    raise PostgresUpgradeError(f"Old main directory {self.main_dir} does not exist or is inaccessible.")

                pg_version_path = os.path.join(self.main_dir, 'PG_VERSION')
                if not os.path.isfile(pg_version_path):
                    raise PostgresUpgradeError(f"{pg_version_path} does not exist or is inaccessible.")

                postmaster_pid_path = os.path.join(self.main_dir, 'postmaster.pid')
                if os.path.exists(postmaster_pid_path):
                    raise PostgresUpgradeError(f"{postmaster_pid_path} exists; is the database running?")

        # Create run directory
        pathlib.Path(f"/var/run/postgresql/{version}-main.pg_stat_tmp/").mkdir(parents=True, exist_ok=True)

        self.bin_dir = f"/usr/lib/postgresql/{version}/bin/"

        if not _dir_exists_and_accessible(self.bin_dir):
            raise PostgresUpgradeError(f"Binaries directory {self.bin_dir} does not exist or is inaccessible.")
        if not _dir_exists_and_accessible(self.bin_dir):
            raise PostgresUpgradeError(f"Binaries directory {self.bin_dir} does not exist or is inaccessible.")

        if target_version:

            self.initdb = os.path.join(self.bin_dir, 'initdb')
            if not os.access(self.initdb, os.X_OK):
                raise PostgresUpgradeError(f"'initdb' at {self.initdb} does not exist.")

            self.pg_upgrade = os.path.join(self.bin_dir, 'pg_upgrade')
            if not os.access(self.pg_upgrade, os.X_OK):
                raise PostgresUpgradeError(f"'pg_upgrade' at {self.pg_upgrade} does not exist.")

            self.vacuumdb = os.path.join(self.bin_dir, 'vacuumdb')
            if not os.access(self.vacuumdb, os.X_OK):
                raise PostgresUpgradeError(f"'vacuumdb' at {self.vacuumdb} does not exist.")

            self.postgres = os.path.join(self.bin_dir, 'postgres')
            if not os.access(self.postgres, os.X_OK):
                raise PostgresUpgradeError(f"'postgres' at {self.postgres} does not exist.")

        logging.info(f"Creating temporary configuration for version {version}...")
        self.tmp_conf_dir = f"/var/tmp/postgresql/conf/{version}"
        if os.path.exists(self.tmp_conf_dir):
            logging.debug(f"Cleaning up {self.tmp_conf_dir} first...")
            shutil.rmtree(self.tmp_conf_dir)
        current_postgresql_config_path = self._current_postgresql_config_path()
        shutil.copytree(current_postgresql_config_path, self.tmp_conf_dir)

        with open(os.path.join(self.tmp_conf_dir, 'postgresql.conf'), 'a') as postgresql_conf:
            postgresql_conf.write(f"""

            port = {port}
            data_directory = '/var/lib/postgresql/{version}/main'
            hba_file = '{self.tmp_conf_dir}/pg_hba.conf'
            ident_file = '{self.tmp_conf_dir}/pg_ident.conf'
            external_pid_file = '/var/run/postgresql/{version}-main.pid'
            cluster_name = '{version}/main'
            stats_temp_directory = '/var/run/postgresql/{version}-main.pg_stat_tmp'

            {extra_postgres_config}

            """)


@dataclasses.dataclass
class _PostgresVersionPair(object):
    """
    Version pair to upgrade between.

    Must be different by exactly one version number, e.g. 11 and 12.
    """
    old_version: _PostgresVersion
    new_version: _PostgresVersion


def postgres_upgrade(source_version: int, target_version: int) -> None:
    """
    Upgrade PostgreSQL from source version up to target version.

    :param source_version: Source dataset version, e.g. 11.
    :param target_version: Target dataset version, e.g. 13.
    """
    logging.debug(f"Source version: {source_version}; target version: {target_version}")

    if not _dir_exists_and_accessible(POSTGRES_DATA_DIR):
        raise PostgresUpgradeError(f"{POSTGRES_DATA_DIR} does not exist or is inaccessible.")

    if getpass.getuser() != POSTGRES_USER:
        raise PostgresUpgradeError(f"This script is to be run as '{POSTGRES_USER}' user.")

    if target_version <= source_version:
        raise PostgresUpgradeError(
            f"Target version {target_version} is not newer than source version {source_version}."
        )

    logging.info("Updating memory configuration...")
    subprocess.check_call(['/opt/mediacloud/bin/update_memory_config.sh'])

    # Remove cruft that might have been left over from last attempt to do the upgrade
    patterns = [
        'pg_*.log',
        'pg_*.custom',
        'pg_upgrade_dump_globals.sql',
    ]
    for pattern in patterns:
        for file in glob.glob(os.path.join(POSTGRES_DATA_DIR, pattern)):
            logging.debug(f"Deleting {file}...")
            os.unlink(pattern)

    ram_size = int(subprocess.check_output(['/container_memory_limit.sh']).decode('utf-8'))
    assert ram_size, "RAM size can't be zero."
    new_maintenance_work_mem = int(ram_size / 10)
    logging.info(f"New maintenance work memory limit: {new_maintenance_work_mem} MB")
    maintenance_work_mem_statement = f'maintenance_work_mem = {new_maintenance_work_mem}MB'

    # Work out upgrade pairs
    # (initialize the pairs first so that _PostgresVersion() gets a chance to test environment first)
    upgrade_pairs = []
    current_port = 50432
    for version in range(source_version, target_version):
        upgrade_pairs.append(
            _PostgresVersionPair(
                old_version=_PostgresVersion(
                    version=version,
                    target_version=False,
                    starting_version=(version == source_version),
                    port=current_port,
                    extra_postgres_config='',
                ),
                new_version=_PostgresVersion(
                    version=version + 1,
                    target_version=True,
                    starting_version=False,
                    port=current_port + 1,
                    extra_postgres_config=maintenance_work_mem_statement,
                )
            ))
        current_port = current_port + 2

    for pair in upgrade_pairs:

        logging.info(f"Upgrading from {pair.old_version.version} to {pair.new_version.version}...")

        logging.info("Running initdb...")
        pathlib.Path(pair.new_version.main_dir).mkdir(parents=True, exist_ok=True)
        subprocess.check_call([
            pair.new_version.initdb,
            '--pgdata', pair.new_version.main_dir,
            '--data-checksums',
            '--encoding', 'UTF-8',
            '--lc-collate', 'en_US.UTF-8',
            '--lc-ctype', 'en_US.UTF-8',
        ])

        upgrade_command = [
            pair.new_version.pg_upgrade,
            '--jobs', str(multiprocessing.cpu_count()),
            '--old-bindir', pair.old_version.bin_dir,
            '--new-bindir', pair.new_version.bin_dir,
            '--old-datadir', pair.old_version.main_dir,
            '--new-datadir', pair.new_version.main_dir,
            '--old-port', str(pair.old_version.port),
            '--new-port', str(pair.new_version.port),
            '--old-options', f" -c config_file={pair.old_version.tmp_conf_dir}/postgresql.conf",
            '--new-options', f" -c config_file={pair.new_version.tmp_conf_dir}/postgresql.conf",
            '--link',
            '--verbose',
        ]

        logging.info("Testing if clusters are compatible...")
        subprocess.check_call(upgrade_command + ['--check'], cwd=POSTGRES_DATA_DIR)

        logging.info("Upgrading...")
        subprocess.check_call(upgrade_command, cwd=POSTGRES_DATA_DIR)

        logging.info("Cleaning up old data directory...")
        shutil.rmtree(pair.old_version.data_dir)

        logging.info("Cleaning up scripts...")
        for script in [
            'analyze_new_cluster.sh',
            'delete_old_cluster.sh',
            'pg_upgrade_internal.log',
            'pg_upgrade_server.log',
            'pg_upgrade_utility.log',
        ]:
            script_path = os.path.join(POSTGRES_DATA_DIR, script)
            if os.path.isfile(script_path):
                os.unlink(script_path)

        logging.info(f"Done upgrading from {pair.old_version.version} to {pair.new_version.version}")

    current_version = upgrade_pairs[-1].new_version

    logging.info("Starting PostgreSQL to run VACUUM ANALYZE...")
    postgres_proc = subprocess.Popen([
        current_version.postgres,
        '-D', current_version.main_dir,
        '-c', f'config_file={current_version.tmp_conf_dir}/postgresql.conf',
    ])

    while not _tcp_port_is_open(port=current_version.port):
        logging.info("Waiting for PostgreSQL to come up...")
        time.sleep(1)

    logging.info("Running VACUUM ANALYZE...")
    logging.info("(monitor locks while running that because PostgreSQL might decide to do autovacuum!)")
    subprocess.check_call([
        current_version.vacuumdb,
        '--port', str(current_version.port),
        '--all',
        '--verbose',
        '--jobs', str(multiprocessing.cpu_count()),
        # No --analyze-in-stages because we're ready to wait for the full statistics
    ])

    logging.info("Waiting for PostgreSQL to shut down...")
    postgres_proc.send_signal(signal.SIGTERM)
    postgres_proc.wait()

    logging.info("Done!")


def main():
    parser = argparse.ArgumentParser(description="Upgrade PostgreSQL dataset.")
    parser.add_argument("-s", "--source_version", type=int, required=True,
                        help="Version to upgrade from")
    parser.add_argument("-t", "--target_version", type=int, required=True,
                        help="Version to upgrade to")
    args = parser.parse_args()

    postgres_upgrade(source_version=args.source_version, target_version=args.target_version)


if __name__ == '__main__':
    main()
