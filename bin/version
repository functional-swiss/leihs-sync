#!/usr/bin/env ruby

require 'bundler/inline'

gemfile do
  source 'https://rubygems.org'
  gem 'git'
  gem 'pry'
end

require 'date'
require 'git'
require 'pry'
require 'yaml'

PROJECT_DIR = File.dirname(File.expand_path('..', __FILE__))
g = Git.open(PROJECT_DIR)
head = g.object('HEAD')

File.open(Pathname(PROJECT_DIR).join("resources").join("version.yml"), 'w') do |f|
  f.write({
    'commit-id' => head.sha,
    'commit-timestamp' => head.committer_date.to_datetime().iso8601,
    'build-timestamp' => DateTime.now.iso8601
  }.to_yaml)
end


# vi: ft=ruby
