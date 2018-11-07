import React from 'react';
import PropTypes from 'prop-types';
import moment from 'moment';
import 'moment-duration-format';

class TimeIntervalComponent extends React.Component {
    static propTypes = {
        editable: PropTypes.bool.isRequired,
        value: PropTypes.number.isRequired,
        didUpdate: PropTypes.func
    };

    constructor(props){
        super(props);

        this.state = {
            hoursSet: 0,
            minutesSet: 0,
            secondsSet:0
        }

    }

    componentDidUpdate(oldProps,oldState){
        if(oldProps.value!==this.props.value){
            const d = moment.duration(this.props.value,"seconds");
            this.setState({hoursSet: d.hours(), minutesSet: d.minutes(), secondsSet: d.seconds()})
        }
        if(oldState.hoursSet!==this.state.hoursSet || oldState.minutesSet!==this.state.minutesSet || oldState.secondsSet!==this.state.secondsSet){
            if(this.props.didUpdate) this.props.didUpdate(this.state.hoursSet*3600+this.state.minutesSet*60+this.state.minutesSet)
        }
    }

    render(){
        if(this.props.editable){
            return <span className="duration">
                <input value={this.state.hoursSet} onChange={evt=>this.setState({hoursSet: evt.target.value})}/> hours
                <input value={this.state.minutesSet} onChange={evt=>this.setState({minutesSet: evt.target.value})}/> hours
                <input value={this.state.secondsSet} onChange={evt=>this.setState({secondsSet: evt.target.value})}/> hours
            </span>
        } else {
            let fmtStringParts = [];
            if (this.state.hoursSet > 0) fmtStringParts = fmtStringParts.concat(["h [hours]"]);
            if (this.state.minutesSet() > 0) fmtStringParts = fmtStringParts.concat(["m [minutes]"]);
            if (this.state.secondsSet() > 0) fmtStringParts = fmtStringParts.concat(["s [seconds]"]);
            const formatString = fmtStringParts.join(", ");

            return <span className="duration">{d.format(formatString)}</span>
        }
    }
}

export default TimeIntervalComponent;