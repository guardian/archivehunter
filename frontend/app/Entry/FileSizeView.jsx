import React from 'react';
import PropTypes from 'prop-types';

class FileSizeView extends React.Component {
    static propTypes = {
        rawSize: PropTypes.number.isRequired
    };

    constructor(props){
        super(props);
    }

    countThousands(num, iteration){
        if(num>1000){
            return this.countThousands(num/1000,iteration+1)
        } else {
            return {
                value: num,
                thousands: iteration
            }
        }
    }

    getPostfix(thousands){
        const postfixes = ["b", "Kb", "Mb", "Gb", "Tb", "Pb", "Eb"];
        if(thousands>postfixes.length) return "ridiculous";
        return postfixes[thousands];
    }

    render(){
        const result = this.countThousands(this.props.rawSize, 0);
        return <span className="file-size">{result.value} {this.getPostfix(result.thousands)}</span>
    }
}

export default FileSizeView;