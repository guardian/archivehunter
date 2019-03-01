import React from 'react';
import { FontAwesomeIcon } from '@fortawesome/react-fontawesome'
import PropTypes from 'prop-types';

class RefreshButton extends React.Component {
    static propTypes = {
        isRunning: PropTypes.bool.isRequired,
        clickedCb: PropTypes.func.isRequired,
        showText: PropTypes.bool,
        caption: PropTypes.string
    };

    constructor(props){
        super(props);
        this.state = {
            spinAngle: 0
        }
    }

    render() {
        return <span className="clickable" onClick={this.props.clickedCb}>
            <FontAwesomeIcon icon="redo-alt"
                                className={this.props.isRunning ? "button-icon spin" : "button-icon"}/>
            {
                this.props.showText ?
                    this.props.caption ? this.props.caption : "Refresh"
                    : ""
            }
        </span>
    }
}

export default RefreshButton;