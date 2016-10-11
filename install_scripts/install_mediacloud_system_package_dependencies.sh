#!/bin/bash

set -u
set -o errexit


VAGRANT_URL_DEBIAN="https://releases.hashicorp.com/vagrant/1.8.6/vagrant_1.8.6_x86_64.deb"
ERLANG_APT_GPG_KEY_URL="http://packages.erlang-solutions.com/ubuntu/erlang_solutions.asc"
ERLANG_APT_REPOSITORY_URL="http://packages.erlang-solutions.com/ubuntu"
RABBITMQ_PACKAGECLOUD_SCRIPT="https://packagecloud.io/install/repositories/rabbitmq/rabbitmq-server/script.deb.sh"

# Erlang version to install on Ubuntu < 16.04:
#
# Update rabbitmq_wrapper.sh too!
#
# Newest Erlang version (18.3 at the time of writing) has memory handling issues, see:
# https://groups.google.com/forum/#!topic/rabbitmq-users/7K0Ac5tWUIY
#
ERLANG_OLD_UBUNTU_APT_VERSION="1:17.5.3"


function echo_vagrant_instructions {
    cat <<EOF
You might want to install Vagrant to set up automatic Media Cloud unit testing
on VirtualBox / Amazon EC2 machines. Download and install Vagrant from:

http://downloads.vagrantup.com/

You don't need Vagrant to run Media Cloud, so install it only if you know what
you're doing.

When you have installed Vagrant (or chose to not install it at all), make sure
that you have "vagrant" binary somewhere (e.g. in /usr/bin/vagrant) and run
this script again with the environment variable SKIP_VAGRANT_TEST being set as
such:

SKIP_VAGRANT_TEST=1 $0
EOF
}

# Version comparison functions
function verlte() {
    [  "$1" = "`echo -e "$1\n$2" | sort -V | head -n1`" ]
}

function verlt() {
    [ "$1" = "$2" ] && return 1 || verlte "$1" "$2"
}


echo "Installing Media Cloud system dependencies..."
echo

if [ `uname` == 'Darwin' ]; then

    # Mac OS X

    if [ ! -x /usr/local/bin/brew ]; then
        cat <<EOF
You'll need Homebrew <http://mxcl.github.com/homebrew/> to install the required
packages on Mac OS X. It might be possible to do that manually with
Fink <http://www.finkproject.org/> or MacPorts <http://www.macports.org/>, but
you're at your own here.
EOF
        exit 1
    fi

    if [ ! -x /usr/bin/gcc ]; then
        cat <<EOF
As a dependency to Homebrew, you need to install Xcode (available as a free
download from Mac App Store or from http://developer.apple.com/) and Xcode's
"Command Line Tools" (open Xcode, go to "Xcode" -> "Preferences...", select
"Downloads", choose "Components", click "Install" near the "Command Line Tools"
entry, wait for a while.
EOF
        exit 1
    fi

    echo "Installing Media Cloud dependencies with Homebrew..."
    brew install \
        graphviz --with-bindings \
        coreutils curl homebrew/dupes/tidy libyaml gawk cpanminus \
        netcat openssl rabbitmq libyaml python3

    echo "Installing Media Cloud dependencies with cpanm..."
    sudo cpanm \
        XML::Parser XML::SAX::Expat XML::LibXML XML::LibXML::Simple \
        Test::WWW::Mechanize OpenGL DBD::Pg Perl::Tidy HTML::Parser YAML \
        YAML::LibYAML YAML::Syck List::AllUtils List::MoreUtils Readonly \
        Readonly::XS GraphViz Graph Graph::Writer::GraphViz \
        HTML::Entities version Lingua::Stem::Snowball

   if [ ! "${SKIP_VAGRANT_TEST:+x}" ]; then
        if [ ! -x /usr/bin/vagrant ]; then
            echo_vagrant_instructions
            exit 1
        fi
    fi

else

    # assume Ubuntu
    source /etc/lsb-release

    echo "Installing curl..."
    sudo apt-get -y install curl

    # Apt's versions of Supervisor, Vagrant, RabbitMQ are too old
    OBSOLETE_APT_PACKAGES=(supervisor vagrant rabbitmq-server)
    for obsolete_package in "${OBSOLETE_APT_PACKAGES[@]}"; do
        dpkg-query -l "$obsolete_package" | grep "^ii" >/dev/null 2>&1 && {
            echo "Installed package '$obsolete_package' from APT is too old, removing..."
            sudo apt-get -y remove $obsolete_package
        }
    done

    # Ubuntu < 14.04 APT's version of Erlang is too old (needed by RabbitMQ)
    if verlt "$DISTRIB_RELEASE" "14.04"; then

        echo "Removing system package Erlang on Ubuntu 12.04 because it's too old..."
        sudo apt-get -y remove erlang*

        # Install and hold specific version of Erlang
        echo "Installing Erlang from Erlang Solutions..."
        curl "$ERLANG_APT_GPG_KEY_URL" | sudo apt-key add -
        echo "deb $ERLANG_APT_REPOSITORY_URL precise contrib" | \
            sudo tee -a /etc/apt/sources.list.d/erlang-solutions.list
        sudo apt-get -y update
        sudo apt-get -y install esl-erlang="$ERLANG_OLD_UBUNTU_APT_VERSION" erlang-mode="$ERLANG_OLD_UBUNTU_APT_VERSION"
        sudo apt-mark hold erlang-mode esl-erlang
    fi

    # Ubuntu (all versions) APT's version of RabbitMQ is too old
    # (we need 3.6.0+ to support priorities and lazy queues)
    echo "Adding RabbitMQ GPG key for Apt..."
    curl -s "$RABBITMQ_PACKAGECLOUD_SCRIPT" | sudo bash

    # OpenJDK version to install
    if verlt "$DISTRIB_RELEASE" "16.04"; then
        # Solr 6+ requires Java 8 which is unavailable before 16.04
        echo "Adding Java 8 PPA repository to Ubuntu 12.04..."
        sudo apt-get -y install python-software-properties
        sudo add-apt-repository -y ppa:openjdk-r/ppa
        sudo apt-get update
    fi

    # Python version to install
    if verlt "$DISTRIB_RELEASE" "16.04"; then
        # We require at least Python 3.5 (12.04 only has 3.2 which doesn't work with newest Pip)
        echo "Adding Python 3.5 PPA repository to Ubuntu 12.04..."
        sudo apt-get -y install python-software-properties
        sudo add-apt-repository -y ppa:fkrull/deadsnakes
        sudo apt-get update
    fi

    # Install the rest of the packages
    echo "Installing Media Cloud dependencies with APT..."
    sudo apt-get --assume-yes install \
        expat libexpat1-dev libxml2-dev gawk postgresql-server-dev-all \
        libdb-dev libtest-www-mechanize-perl libtidy-dev \
        libopengl-perl libgraph-writer-graphviz-perl libgraphviz-perl \
        graphviz graphviz-dev graphviz-doc libgraphviz-dev libyaml-syck-perl \
        liblist-allutils-perl liblist-moreutils-perl libreadonly-perl \
        libreadonly-xs-perl curl python2.7 python2.7-dev python-pip \
        libxml2-dev libxslt1-dev libxslt1-dbg libxslt1.1 build-essential make gcc g++ \
        cpanminus perl-doc liblocale-maketext-lexicon-perl openjdk-8-jdk \
        pandoc netcat rabbitmq-server libyaml-dev unzip python3.5

    # Choose to use OpenJDK 8 by default
    echo "Selecting Java 8..."
    sudo update-java-alternatives -s `update-java-alternatives --list | grep java-1.8 | awk '{ print $3 }'`

    # Disable system-wide RabbitMQ server (we will start and use our very own instance)
    echo "Stopping and disabling system's RabbitMQ instance..."
    sudo update-rc.d rabbitmq-server disable
    sudo service rabbitmq-server stop
    
    # Install an up-to-date version of Vagrant
    if [ ! "${SKIP_VAGRANT_TEST:+x}" ]; then
        if [ ! -x /usr/bin/vagrant ]; then

            echo "Installing Vagrant..."

            # Try to download and install
            VAGRANT_TEMP_DIR=`mktemp -d -t vagrantXXXXX`
            VAGRANT_TEMP_FILE="$VAGRANT_TEMP_DIR/vagrant.deb"

            wget --quiet -O "$VAGRANT_TEMP_FILE" "$VAGRANT_URL_DEBIAN" || {
                echo "Unable to fetch Vagrant from $VAGRANT_URL_DEBIAN; maybe the URL is outdated?"
                echo
                echo_vagrant_instructions
                exit 1
            }

            sudo dpkg -i "$VAGRANT_TEMP_FILE" || {
                echo "Unable to install Vagrant from $VAGRANT_TEMP_FILE."
                echo
                echo_vagrant_instructions
                exit 1
            }

            rm -rf "$VAGRANT_TEMP_DIR"

            if [ ! -x /usr/bin/vagrant ]; then    # Installed?
                echo "I have tried to install Vagrant manually but failed."
                echo
                echo_vagrant_instructions
                exit 1
            fi

            # Install AWS plugin (https://github.com/mitchellh/vagrant-aws)
            vagrant plugin install vagrant-aws
        fi
    fi

fi
