import React from 'react';
import PropTypes from 'prop-types';

class MediaDurationComponent extends React.Component {
    static propTypes = {
        value: PropTypes.number.isRequired //the duration in seconds
    };

    breakdownTime() {
        const mins = Math.floor(this.props.value/60.0);
        const hrs = mins>0 ? Math.floor(this.props.value/3600.0) : 0;
        const seconds = Math.ceil(this.props.value - hrs*3600 - mins*60);

        if(hrs===0 && mins===0){
            return this.props.value.toString() + " seconds"
        } else if(hrs===0){
            return mins.toString() + " minutes, " + seconds.toString() + " seconds"
        } else {
            return hrs.toString() + " hours, " + mins.toString() + " minutes, " + seconds.toString() + " seconds"
        }
    }

    render(){
        return this.props.value ? <span>{this.breakdownTime()}</span> : <p className="information">no data</p>
    }
}

export default MediaDurationComponent;