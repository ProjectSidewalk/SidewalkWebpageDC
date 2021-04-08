module.exports = function(grunt) {

    // 1. All configuration goes here
    grunt.initConfig({
        pkg: grunt.file.readJSON('package.json'),

        concat: {
            dist_svl: {
                src: [
                    'public/javascripts/SVLabel/lib/gsv/GSVPano.js',
                    'public/javascripts/SVLabel/lib/gsv/GSVPanoPointCloud.js',
                    'public/javascripts/SVLabel/src/SVLabel/alert/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/canvas/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/data/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/game/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/keyboard/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/label/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/menu/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/mission/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/modal/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/navigation/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/neighborhood/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/onboarding/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/panorama/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/ribbon/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/status/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/task/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/user/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/util/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/zoom/*.js',
                    'public/javascripts/SVLabel/src/SVLabel/*.js'
                ],
                dest: 'public/javascripts/SVLabel/build/SVLabel.js'
            },
            dist_progress: {
                src: [
                    'public/javascripts/Progress/src/*.js'
                ],
                dest: 'public/javascripts/Progress/build/Progress.js'
            },
            dist_admin: {
                src: [
                    'public/javascripts/Admin/src/*.js'
                ],
                dest: 'public/javascripts/Admin/build/Admin.js'
            },
            dist_faq: {
                src: [
                    'public/javascripts/FAQ/src/*.js'
                ],
                dest: 'public/javascripts/FAQ/build/FAQ.js'
            }
        },
        concat_css: {
            all: {
                src: [
                    'public/javascripts/SVLabel/css/svl.css',
                    'public/javascripts/SVLabel/css/*.css'
                    ],
                dest: 'public/javascripts/SVLabel/build/SVLabel.css'
            }
        },
        watch : {
            scripts: {
                files: [
                    'public/javascripts/SVLabel/src/**/*.js',
                    'public/javascripts/SVLabel/css/*.css',
                    'public/javascripts/Progress/src/**/*.js',
                    'public/javascripts/Admin/src/**/*.js',
                    'public/javascripts/FAQ/src/*.js'
                ],
                tasks: [
                    'concat',
                    'concat_css'
                ],
                options: {
                    interrupt: true
                }
            }
        }
    });

    // 3. Where we tell Grunt we plan to use this plug-in.
    grunt.loadNpmTasks('grunt-contrib-concat');
    grunt.loadNpmTasks('grunt-concat-css');
    grunt.loadNpmTasks('grunt-contrib-watch');

    // 4. Where we tell Grunt what to do when we type "grunt" into the terminal.
    grunt.registerTask('default', ['concat', 'concat_css']);
    grunt.registerTask('dist', ['concat:dist_svl', 'concat:dist_progress', 'concat:dist_admin']);
};
